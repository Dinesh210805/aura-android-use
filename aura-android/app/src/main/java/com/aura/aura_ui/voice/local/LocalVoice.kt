package com.aura.aura_ui.voice.local

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.aura.aura_ui.agent.voice.vad.SpeechEvent
import com.aura.aura_ui.audio.AuraTTSManager
import com.aura.aura_ui.voice.CommAudio
import com.aura.aura_ui.voice.LocalVoices
import com.aura.aura_ui.voice.MicStream
import com.aura.aura_ui.voice.MoonshineStt
import com.aura.aura_ui.voice.PiperSpeaker
import com.aura.aura_ui.voice.VoiceIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * On-phone ears and mouth ([VoiceIo]): Silero finds speech, Moonshine transcribes each utterance,
 * Piper speaks. Nothing leaves the phone.
 *
 * - Contract: an utterance is the audio from Silero's speech start (with [PRE_ROLL_MS] before it)
 *   to its end; it is transcribed once, so there are no partial transcripts. While Piper speaks the
 *   mic stays open on the echo-cancelled path, and the person talking over AURA cuts it off.
 * - Fails: without the Moonshine model, Android's own speech recogniser is used (offline when it
 *   can, no talking over AURA). Without a downloaded Piper voice, Android's text-to-speech speaks.
 * - Reads: `LocalVoiceModels` files; the voice chosen in Settings (`aura_voice_settings` /
 *   `selected_voice_id`).
 */
class LocalVoice(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: VoiceIo.Listener,
) : VoiceIo {

    private val voice = LocalVoices.byId(
        context.getSharedPreferences("aura_voice_settings", Context.MODE_PRIVATE).getString("selected_voice_id", null),
    )
    private val systemTts by lazy { AuraTTSManager(context.applicationContext) }
    private val useMoonshine = MoonshineStt.isAvailable(context)
    private val mic = MicStream(context, scope, ::onMic)
    private var recognizer: RecognizerEars? = null

    @Volatile override var listening = false
        private set
    @Volatile override var speaking = false
        private set
    @Volatile private var speakingSinceMs = 0L
    @Volatile private var closed = false

    private val preRoll = ArrayDeque<ByteArray>()
    private val utterance = ByteArrayOutputStream()
    @Volatile private var inSpeech = false
    @Volatile private var utteranceMs = 0

    override suspend fun open(): Boolean {
        if (useMoonshine) scope.launch(Dispatchers.Default) { runCatching { MoonshineStt.preload(context) } }
        PiperSpeaker.preload(context, voice)
        if (!useMoonshine && !SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onTrouble("Download the on-device speech model in Settings → Voice to talk to AURA offline.")
            return false
        }
        return true
    }

    override fun listen(on: Boolean) {
        if (closed || on == listening) return
        listening = on
        if (!useMoonshine) {
            if (on) {
                stopSpeaking() // the system recogniser cannot hear past AURA's voice
                recognizer = RecognizerEars(context, listener).also { it.start() }
            } else {
                recognizer?.stop()
                recognizer = null
            }
            return
        }
        if (on) {
            CommAudio.enter(context)
            mic.start()
        } else {
            mic.stop()
            synchronized(utterance) {
                utterance.reset()
                preRoll.clear()
            }
            inSpeech = false
            listener.onLevel(0f)
        }
    }

    private fun onMic(pcm: ByteArray, events: List<SpeechEvent>, level: Float) {
        if (!speaking) listener.onLevel(level)
        var ended = false
        synchronized(utterance) {
            for (e in events) when (e) {
                SpeechEvent.SpeechStart -> {
                    inSpeech = true
                    utteranceMs = 0
                    utterance.reset()
                    preRoll.forEach { utterance.write(it) }
                    preRoll.clear()
                    listener.onUserSpeech(true)
                    if (speaking && System.currentTimeMillis() - speakingSinceMs > AEC_SETTLE_MS) stopSpeaking()
                }
                SpeechEvent.SpeechEnd -> ended = true
            }
            if (inSpeech) {
                utterance.write(pcm)
                utteranceMs += MicStream.CHUNK_MS
                if (utteranceMs >= MAX_UTTERANCE_MS) ended = true
            } else {
                preRoll.addLast(pcm)
                while (preRoll.size > PRE_ROLL_MS / MicStream.CHUNK_MS) preRoll.removeFirst()
            }
        }
        if (ended && inSpeech) finishUtterance()
    }

    private fun finishUtterance() {
        val audio = synchronized(utterance) {
            inSpeech = false
            utterance.toByteArray().also { utterance.reset() }
        }
        listener.onUserSpeech(false)
        if (audio.size < MicStream.RATE * 2 * MIN_UTTERANCE_MS / 1000) return
        listener.onHearing("…")
        scope.launch(Dispatchers.Default) {
            val text = runCatching { MoonshineStt.transcribe(context, audio) }
                .onFailure { Log.w(TAG, "transcription failed: ${it.message}") }
                .getOrNull()?.trim().orEmpty()
            listener.onHearing("")
            if (text.isNotEmpty() && !closed) listener.onHeard(text)
        }
    }

    override fun say(text: String) {
        if (closed || text.isBlank()) return
        stopSpeaking()
        speaking = true
        speakingSinceMs = System.currentTimeMillis()
        val done = { completed: Boolean ->
            if (speaking) {
                speaking = false
                listener.onLevel(0f)
                listener.onSpoken(completed)
            }
        }
        if (useMoonshine) CommAudio.enter(context)
        val piper = PiperSpeaker.speak(context, text, voice, voiceChat = useMoonshine) { done(true) }
        if (!piper) {
            listener.onSpeaking(text)
            systemTts.speak(text, voice.id) { done(true) }
        }
    }

    override fun stopSpeaking() {
        if (!speaking) return
        speaking = false
        PiperSpeaker.stop()
        systemTts.stop()
        listener.onLevel(0f)
        listener.onSpoken(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        listen(false)
        stopSpeaking()
        recognizer?.stop()
        CommAudio.leave(context)
    }

    private companion object {
        const val TAG = "LocalVoice"
        const val PRE_ROLL_MS = 400
        const val MIN_UTTERANCE_MS = 350
        const val MAX_UTTERANCE_MS = 30_000
        const val AEC_SETTLE_MS = 500L
    }
}

/**
 * Android's speech recogniser as ears, for phones without the Moonshine model. One utterance per
 * [start]; partial results are passed on as they come.
 * - Contract: main thread only inside; [start] and [stop] may be called from anywhere.
 */
private class RecognizerEars(private val context: Context, private val listener: VoiceIo.Listener) {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null

    fun start() = main.post {
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: run {
            listener.onTrouble("Speech recognition isn't available on this phone.")
            return@post
        }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() = listener.onUserSpeech(true)
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.onUserSpeech(false)
            override fun onError(error: Int) {
                listener.onLevel(0f)
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    Log.w("RecognizerEars", "recogniser error $error")
                }
            }
            override fun onResults(results: Bundle?) {
                listener.onLevel(0f)
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(listener::onHeard)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let(listener::onHearing)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
    }

    fun stop() = main.post {
        sr?.let { runCatching { it.cancel(); it.destroy() } }
        sr = null
    }
}
