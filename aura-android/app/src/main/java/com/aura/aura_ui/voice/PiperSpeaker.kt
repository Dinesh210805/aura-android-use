package com.aura.aura_ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.aura.aura_ui.services.AgentStatusRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device neural TTS (Piper / VITS medium via sherpa-onnx), pipelined: chunk N+1 is synthesized
 * while chunk N plays, and the status-strip caption follows the playback head word by word.
 *
 * - Contract: at most one voice loaded at a time (~64 MB model); picking another voice swaps it on
 *   the next [speak]. A new [speak] or [stop] cancels whatever is playing. `onStart` fires on the
 *   player thread when the first audio reaches the speaker; `onDone` fires only for an utterance
 *   that played to the end. Publishes captions via `AgentStatusRegistry.setSpoken`.
 * - Threading: the engine is created, used and released only on the [synth] thread. Releasing it
 *   from anywhere else while a `generate` runs crashes in native code.
 * - Fails: [speak] returns false when the voice isn't downloaded; a load or synthesis error ends
 *   the utterance early (logged) and `onDone` does not fire. An unreadable cache file is re-synthesized.
 * - Reads: the voice's `LocalVoiceModels.piper` files. Reads/Writes: `filesDir/tts-cache/` when `cache` is set.
 * - Why Piper: on phone CPUs it synthesizes at roughly 0.1–0.2× real time, so it keeps ahead of
 *   playback even on low-end chips; larger neural voices (82M-param Kokoro) run slower than real time there.
 * - Why `USAGE_MEDIA`: speaker output at media volume, full band. Call audio is quiet, narrowband
 *   and follows the call volume. Must match `AuraTTSManager.applyAudioRouting`. The exception is a
 *   voice conversation (`voiceChat`): there AURA must hear the person over itself, which needs its
 *   voice on the call path where the echo canceller can remove it.
 */
object PiperSpeaker {
    private const val TAG = "PiperSpeaker"
    private const val SLICE_MS = 100
    private const val DRAIN_SLACK_MS = 500L

    /** Two, not four: on big.LITTLE chips extra threads land on the slow cores and add sync cost. */
    private const val NUM_THREADS = 2

    private class Chunk(val text: String, val samples: FloatArray, val sampleRate: Int)
    private val END = Chunk("", FloatArray(0), 0)

    private class Loaded(val dirName: String, val tts: OfflineTts)

    private val synth = Executors.newSingleThreadExecutor { Thread(it, "piper-synth") }
    private val player = Executors.newSingleThreadExecutor { Thread(it, "piper-play") }
    private val generation = AtomicInteger()
    /** Touched only on [synth]. */
    private var loaded: Loaded? = null
    @Volatile private var track: AudioTrack? = null

    fun isAvailable(context: Context, voice: LocalVoices.Voice): Boolean =
        LocalVoiceModels.isReady(context, voice.model)

    /** Load [voice] ahead of the first reply. Queued on [synth]; Piper loads in well under a second. */
    fun preload(context: Context, voice: LocalVoices.Voice) {
        if (!isAvailable(context, voice)) return
        val app = context.applicationContext
        synth.execute {
            runCatching { engine(app, voice.model) }.onFailure { Log.e(TAG, "Piper preload failed", it) }
        }
    }

    /**
     * Speak [text] in [voice]. With [cache], each chunk's audio is kept on disk and replayed without
     * loading the model, so fixed phrases (the greeting) start instantly. Only pass it for a small fixed set.
     */
    fun speak(
        context: Context,
        text: String,
        voice: LocalVoices.Voice,
        cache: Boolean = false,
        /** True in a voice conversation: plays as call audio so the echo canceller can hear it. */
        voiceChat: Boolean = false,
        onStart: () -> Unit = {},
        onDone: () -> Unit,
    ): Boolean {
        if (!isAvailable(context, voice)) return false
        val gen = generation.incrementAndGet()
        val app = context.applicationContext
        val model = voice.model
        val chunks = SpeechChunker.split(text)
        val full = chunks.joinToString(" ")
        val offsets = PlaybackCaption.offsets(chunks)
        val queue = LinkedBlockingQueue<Chunk>()
        synth.execute {
            try {
                for (chunk in chunks) {
                    if (gen != generation.get()) break
                    val file = if (cache) cacheFile(app, model, chunk) else null
                    queue.put(file?.let { readCached(it, chunk) } ?: synthesize(app, chunk, model).also { c ->
                        file?.let { writeCached(it, c) }
                    })
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Piper synthesis failed: ${t.message}", t)
            } finally {
                queue.put(END)
            }
        }
        player.execute {
            val usage = if (voiceChat) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
            val finished = runCatching { play(queue, gen, full, offsets, usage, onStart) }
                .onFailure { Log.e(TAG, "Piper playback failed: ${it.message}", it) }
                .getOrDefault(false)
            if (finished && gen == generation.get()) onDone()
        }
        return true
    }

    fun stop() {
        generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    /** True when every chunk played; false when cancelled by a newer speak/stop or nothing was produced. */
    private fun play(
        queue: LinkedBlockingQueue<Chunk>,
        gen: Int,
        full: String,
        offsets: IntArray,
        usage: Int,
        onStart: () -> Unit,
    ): Boolean {
        var out: AudioTrack? = null
        var written = 0 // frames handed to the track so far
        val segments = ArrayList<PlaybackCaption.Segment>()
        fun follow(t: AudioTrack) = publish(PlaybackCaption.at(full, segments, t.playbackHeadPosition))
        try {
            while (gen == generation.get()) {
                val chunk = queue.poll(50, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    // The next chunk is still being synthesized; buffered audio keeps playing.
                    out?.let(::follow)
                    continue
                }
                if (chunk === END) {
                    val t = out ?: break
                    drain(t, written, gen) { follow(t) }
                    if (gen == generation.get()) publish(PlaybackCaption.sentence(full, full.length))
                    break
                }
                val t = out ?: newTrack(chunk.sampleRate, usage).also { out = it; track = it; it.play(); onStart() }
                segments += PlaybackCaption.Segment(written, chunk.samples.size, offsets[segments.size], chunk.text.length)
                val slice = chunk.sampleRate * SLICE_MS / 1000
                var offset = 0
                while (offset < chunk.samples.size && gen == generation.get()) {
                    val n = minOf(slice, chunk.samples.size - offset)
                    t.write(chunk.samples, offset, n, AudioTrack.WRITE_BLOCKING)
                    offset += n
                    written += n
                    follow(t)
                }
            }
            return segments.isNotEmpty() && gen == generation.get()
        } finally {
            out?.let {
                if (track === it) track = null
                runCatching { it.stop() }
                it.release()
            }
        }
    }

    /**
     * Wait for the buffered tail to play: write() returns once audio is buffered, not played.
     *
     * - Why the deadline: on some devices a stream track's playback head stops a few frames short
     *   of what was written, which would wait forever and never fire `onDone`.
     */
    private fun drain(t: AudioTrack, written: Int, gen: Int, onTick: () -> Unit) {
        val remainingMs = (written - t.playbackHeadPosition).coerceAtLeast(0).toLong() * 1000 / t.sampleRate
        val deadline = System.currentTimeMillis() + remainingMs + DRAIN_SLACK_MS
        while (gen == generation.get() && t.playbackHeadPosition < written && System.currentTimeMillis() < deadline) {
            onTick()
            Thread.sleep(30)
        }
    }

    private fun publish(words: String) {
        if (words.isNotBlank()) AgentStatusRegistry.setSpoken(words)
    }

    private fun synthesize(context: Context, text: String, model: LocalVoiceModels.Model): Chunk {
        val tts = engine(context, model)
        val started = System.currentTimeMillis()
        val audio = tts.generate(text, 0, 1.0f)
        // The real-time factor on this device: < 1 keeps ahead of playback. Read this on a low-end phone.
        val seconds = audio.samples.size / audio.sampleRate.toFloat()
        val ms = System.currentTimeMillis() - started
        Log.d(TAG, "synth $ms ms for ${"%.1f".format(seconds)} s (RTF ${"%.2f".format(ms / 1000f / seconds)}): ${text.take(40)}")
        return Chunk(text, audio.samples, audio.sampleRate)
    }

    /** Keyed by voice: every Piper voice is speaker 0, so a speaker id alone would replay the old voice. */
    private fun cacheFile(context: Context, model: LocalVoiceModels.Model, text: String): File =
        File(context.filesDir, "tts-cache/${model.dirName}-${text.hashCode().toUInt()}.pcm")

    /** File layout: sample rate (int), sample count (int), float samples. Null when missing or corrupt. */
    private fun readCached(file: File, text: String): Chunk? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val rate = input.readInt()
            Chunk(text, FloatArray(input.readInt()) { input.readFloat() }, rate)
        }
    }.getOrNull()

    private fun writeCached(file: File, chunk: Chunk) {
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            DataOutputStream(part.outputStream().buffered()).use { out ->
                out.writeInt(chunk.sampleRate)
                out.writeInt(chunk.samples.size)
                chunk.samples.forEach(out::writeFloat)
            }
            part.renameTo(file)
        }.onFailure { Log.w(TAG, "TTS cache write failed: ${it.message}") }
    }

    /** The engine for [model], swapping out any other voice first. [synth] thread only. */
    private fun engine(context: Context, model: LocalVoiceModels.Model): OfflineTts {
        loaded?.takeIf { it.dirName == model.dirName }?.let { return it.tts }
        loaded?.tts?.release()
        loaded = null
        return load(context, model).also { loaded = Loaded(model.dirName, it) }
    }

    private fun load(context: Context, model: LocalVoiceModels.Model): OfflineTts {
        val d = LocalVoiceModels.dir(context, model).absolutePath
        val started = System.currentTimeMillis()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$d/${model.files.first()}",
                    tokens = "$d/tokens.txt",
                    dataDir = "$d/espeak-ng-data",
                ),
                numThreads = NUM_THREADS,
            ),
        )
        // assetManager = null → paths are plain files, not APK assets.
        return OfflineTts(assetManager = null, config = config).also {
            Log.i(TAG, "${model.dirName} loaded in ${System.currentTimeMillis() - started} ms, ${it.sampleRate()} Hz")
        }
    }

    private fun newTrack(sampleRate: Int, usage: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
