package com.aura.aura_ui.voice.live

import android.content.Context
import android.util.Base64
import android.util.Log
import com.aura.aura_ui.agent.voice.vad.SpeechEvent
import com.aura.aura_ui.audio.PcmStreamPlayer
import com.aura.aura_ui.voice.CommAudio
import com.aura.aura_ui.voice.MicStream
import com.aura.aura_ui.voice.VoiceIo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gemini 3.8 Live as AURA's ears and mouth ([VoiceIo]): the person's speech becomes text for
 * AgentCore, and AgentCore's words become speech. Live decides nothing.
 *
 * - Contract: [open] connects with the user's key and waits for `setupComplete`. Audio is played
 *   only while a [say] is being answered (the output gate): anything Live volunteers on its own is
 *   dropped, so even a talkative model can never put words in AURA's mouth. An utterance is final
 *   when speech has ended (Silero's local end, or the server's) and no transcript has arrived for
 *   [SETTLE_MS].
 * - Fails: a setup the server rejects is retried once without optional fields (custom vocabulary).
 *   A dropped link reconnects with the session handle, backing off; after [MAX_DROPS] in a row
 *   [VoiceIo.Listener.onTrouble] is told and the voice stays closed.
 * - Reads/Writes: `wss://generativelanguage.googleapis.com` with the user's key; the speaker and
 *   microphone in communication mode ([CommAudio]).
 */
class LiveVoice(
    private val context: Context,
    private val apiKey: String,
    private val voiceName: String,
    private val vocabulary: () -> List<String>,
    private val scope: CoroutineScope,
    private val listener: VoiceIo.Listener,
) : VoiceIo {

    private var ws: WebSocket? = null

    /** Bumped per connection; frames from an older socket are ignored. */
    private val generation = AtomicInteger()

    @Volatile private var closed = false
    @Volatile private var setup = CompletableDeferred<Boolean>()
    @Volatile private var resumeHandle: String? = null
    @Volatile private var fullSetup = true
    @Volatile private var drops = 0
    @Volatile private var reconnecting = false
    @Volatile private var reconnectWhenIdle = false

    private val mic = MicStream(context, scope, ::onMic)
    @Volatile override var listening = false
        private set
    @Volatile private var sentAudio = false

    // ── mouth ──
    private val player = PcmStreamPlayer()
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private val drainLock = Any()
    private var drainJob: Job? = null
    private var sayWatchdog: Job? = null

    /** True from [say] until that reply has played out or been cut: the output gate. */
    @Volatile private var answering = false

    /** The server has finished generating the reply being played. */
    @Volatile private var replyGenerated = false
    @Volatile private var playbackStartedAtMs = 0L
    private val spokenWords = StringBuilder()

    override val speaking: Boolean get() = answering

    // ── ears ──
    private val heard = StringBuilder()
    @Volatile private var partial = ""
    private var settleJob: Job? = null
    @Volatile private var speechEnded = false

    override suspend fun open(): Boolean {
        CommAudio.enter(context)
        if (connectAndAwait()) return true
        if (fullSetup && !closed) {
            Log.w(TAG, "setup refused with optional fields; retrying the minimal setup")
            fullSetup = false
            if (connectAndAwait()) return true
        }
        listener.onTrouble("Couldn't reach Gemini Live. Check the internet and your Gemini key.")
        return false
    }

    private suspend fun connectAndAwait(): Boolean {
        connect()
        return withTimeoutOrNull(SETUP_TIMEOUT_MS) { setup.await() } == true
    }

    private fun connect() {
        val gen = generation.incrementAndGet()
        setup = CompletableDeferred()
        ws?.close(1000, "replaced")
        val request = Request.Builder().url("${LiveWire.ENDPOINT}?key=$apiKey").build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val vocab = if (fullSetup) runCatching(vocabulary).getOrDefault(emptyList()) else emptyList()
                webSocket.send(LiveWire.setup(LiveWire.Setup(voiceName, vocab, resumeHandle, fullSetup)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = frame(gen, text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = frame(gen, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "closing code=$code reason=$reason")
                webSocket.close(code, null)
                dropped(gen)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(gen)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failed: ${t.message} http=${response?.code}")
                dropped(gen)
            }
        })
    }

    private fun frame(gen: Int, text: String) {
        if (gen != generation.get() || closed) return
        for (event in LiveWire.parse(text)) onEvent(event)
    }

    private fun onEvent(event: LiveEvent) {
        when (event) {
            LiveEvent.SetupComplete -> {
                drops = 0
                setup.complete(true)
            }
            is LiveEvent.Audio -> if (answering) {
                queue.add(event.pcm24k)
                ensureDrain()
            }
            is LiveEvent.HeardPartial -> {
                partial = event.text
                listener.onHearing((heard.toString() + event.text).trim())
            }
            is LiveEvent.Heard -> {
                synchronized(heard) { heard.append(event.text) }
                partial = ""
                listener.onHearing(heard.toString().trim())
                if (speechEnded) armSettle()
            }
            is LiveEvent.Spoken -> if (answering) {
                spokenWords.append(event.text)
                listener.onSpeaking(spokenWords.toString().trim())
            }
            LiveEvent.Interrupted -> if (answering) cutReply()
            LiveEvent.GenerationComplete, LiveEvent.TurnComplete -> {
                if (answering) {
                    replyGenerated = true
                    ensureDrain()
                } else {
                    // A turn the model kept silent about, which is how a user turn ends in relay mode.
                    speechEnded = true
                    armSettle()
                }
                if (reconnectWhenIdle && !answering) reconnectNow()
            }
            is LiveEvent.UserActivity -> {
                listener.onUserSpeech(event.started)
                if (!event.started) {
                    speechEnded = true
                    armSettle()
                } else {
                    speechEnded = false
                }
            }
            is LiveEvent.Resumption -> if (event.resumable && event.handle != null) resumeHandle = event.handle
            is LiveEvent.GoAway -> {
                Log.i(TAG, "goAway, ${event.timeLeftMs} ms left; reconnecting at the next quiet moment")
                if (answering) reconnectWhenIdle = true else reconnectNow()
            }
            is LiveEvent.Usage -> Unit
            is LiveEvent.Unknown -> Log.d(TAG, "unparsed frame: ${event.raw}")
        }
    }

    // ── ears ──

    private fun onMic(pcm: ByteArray, events: List<SpeechEvent>, level: Float) {
        if (!answering) listener.onLevel(level)
        for (e in events) when (e) {
            SpeechEvent.SpeechStart -> {
                speechEnded = false
                settleJob?.cancel()
                listener.onUserSpeech(true)
                // The person talking over AURA. Echo of AURA's own first syllable is ignored.
                if (answering && System.currentTimeMillis() - playbackStartedAtMs > AEC_SETTLE_MS) cutReply()
            }
            SpeechEvent.SpeechEnd -> {
                listener.onUserSpeech(false)
                speechEnded = true
                // Hybrid detection: close the turn on the server now rather than after its own silence.
                scope.launch {
                    delay(STREAM_END_DELAY_MS)
                    if (speechEnded && listening) ws?.send(LiveWire.audioStreamEnd())
                }
                armSettle()
            }
        }
        if (!listening) return
        ws?.send(LiveWire.audio(Base64.encodeToString(pcm, Base64.NO_WRAP)))
        sentAudio = true
    }

    /** Finalize the utterance once transcripts have stopped arriving. */
    private fun armSettle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_MS)
            val text = synchronized(heard) {
                val all = (heard.toString() + partial).trim()
                heard.setLength(0)
                all
            }
            partial = ""
            if (text.isNotEmpty()) listener.onHeard(text)
        }
    }

    override fun listen(on: Boolean) {
        if (closed || on == listening) return
        listening = on
        if (on) {
            CommAudio.enter(context)
            mic.start()
        } else {
            mic.stop()
            settleJob?.cancel()
            synchronized(heard) { heard.setLength(0) }
            partial = ""
            if (sentAudio) ws?.send(LiveWire.audioStreamEnd())
            sentAudio = false
            listener.onLevel(0f)
        }
    }

    // ── mouth ──

    override fun say(text: String) {
        if (closed || text.isBlank()) return
        if (answering) cutReply(notify = false)
        spokenWords.setLength(0)
        replyGenerated = false
        answering = true
        ws?.send(LiveWire.say(text))
        sayWatchdog?.cancel()
        sayWatchdog = scope.launch {
            delay(SAY_TIMEOUT_MS)
            if (answering && playbackStartedAtMs == 0L) {
                Log.w(TAG, "no audio for a SAY in ${SAY_TIMEOUT_MS} ms")
                answering = false
                listener.onSpoken(false)
            }
        }
    }

    override fun stopSpeaking() {
        if (answering) cutReply()
    }

    private fun cutReply(notify: Boolean = true) {
        answering = false
        sayWatchdog?.cancel()
        synchronized(drainLock) {
            queue.clear()
            drainJob?.cancel()
            drainJob = null
            player.interrupt()
        }
        playbackStartedAtMs = 0L
        listener.onLevel(0f)
        if (notify) listener.onSpoken(false)
    }

    /** One writer to the speaker. Ends when the reply is generated and played out. */
    private fun ensureDrain() {
        synchronized(drainLock) {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch(Dispatchers.IO) {
                player.start()
                if (playbackStartedAtMs == 0L) playbackStartedAtMs = System.currentTimeMillis()
                var starvedMs = 0L
                while (isActive && answering) {
                    val chunk = queue.poll()
                    if (chunk != null) {
                        starvedMs = 0L
                        listener.onLevel(MicStream.level(chunk, chunk.size))
                        player.writeChunk(chunk)
                    } else if (replyGenerated) {
                        break
                    } else {
                        if (starvedMs >= STARVE_GIVE_UP_MS) break
                        delay(POLL_MS)
                        starvedMs += POLL_MS
                    }
                }
                if (!answering) return@launch
                // Let the buffered tail reach the ear before saying it is done.
                delay(TAIL_MS)
                player.stop()
                answering = false
                sayWatchdog?.cancel()
                playbackStartedAtMs = 0L
                listener.onLevel(0f)
                listener.onSpoken(true)
                if (reconnectWhenIdle) reconnectNow()
            }
        }
    }

    // ── link ──

    private fun dropped(gen: Int) {
        if (gen != generation.get() || closed) return
        setup.complete(false)
        if (reconnecting) return
        reconnecting = true
        scope.launch {
            try {
                drops++
                if (drops > MAX_DROPS) {
                    listener.onTrouble("Lost the connection to Gemini Live.")
                    return@launch
                }
                if (answering) cutReply()
                delay((BASE_BACKOFF_MS shl (drops - 1).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS))
                if (!closed) connect()
            } finally {
                reconnecting = false
            }
        }
    }

    private fun reconnectNow() {
        reconnectWhenIdle = false
        if (!closed) connect()
    }

    override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        listening = false
        mic.stop()
        settleJob?.cancel()
        sayWatchdog?.cancel()
        if (answering) cutReply(notify = false)
        ws?.close(1000, "bye")
        ws = null
        CommAudio.leave(context)
    }

    private companion object {
        const val TAG = "LiveVoice"
        const val SETUP_TIMEOUT_MS = 8_000L
        const val SETTLE_MS = 550L
        const val STREAM_END_DELAY_MS = 250L
        const val SAY_TIMEOUT_MS = 8_000L
        const val AEC_SETTLE_MS = 500L
        const val POLL_MS = 15L
        const val STARVE_GIVE_UP_MS = 6_000L
        const val TAIL_MS = 180L
        const val MAX_DROPS = 6
        const val BASE_BACKOFF_MS = 400L
        const val MAX_BACKOFF_MS = 6_000L

        /** One client for every session: each OkHttpClient owns thread pools. */
        val client: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(java.time.Duration.ofSeconds(20))
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()
    }
}
