package com.aura.aura_ui.agent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.core.content.ContextCompat
import com.aura.aura_ui.agent.voice.vad.SpeechDetectorFactory
import com.aura.aura_ui.agent.voice.vad.SpeechEvent
import com.aura.aura_ui.agent.voice.vad.SpeechSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text via **Groq Whisper Large v3 Turbo** (cloud) — the best free-tier STT
 * for the on-device agent. Chosen over on-device recognizers because the agent already
 * requires a network round-trip for the LLM, so offline STT buys nothing here, while
 * Whisper gives <5% WER and 52 languages and reuses the existing Groq key.
 *
 * Lifecycle mirrors a push-to-talk mic button: [start] begins capturing 16 kHz mono
 * PCM; [stopAndTranscribe] ends capture, wraps the PCM as a WAV, and uploads it to
 * Groq's `audio/transcriptions` endpoint, returning the text. [cancel] aborts.
 *
 * Captures at 16 kHz (Whisper-native) to keep the upload small. The caller supplies the
 * Groq key (independent of the *LLM* provider the user picked — Whisper is Groq-only);
 * when no Groq key is configured the caller falls back to Android's on-device
 * SpeechRecognizer.
 *
 * End-pointing is Silero VAD (energy-RMS fallback) via [SpeechDetectorFactory]; capture
 * uses VOICE_COMMUNICATION + AcousticEchoCanceler so the mic can stay hot while the
 * assistant's own TTS plays — the [start] onSpeechStart callback is the barge-in hook.
 */
class WhisperSttController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var capturing = false
    private val pcm = ByteArrayOutputStream()

    /**
     * True when a hardware AcousticEchoCanceler attached to the last capture. Only
     * then is full-duplex barge-in (mic hot while TTS plays) safe; without it the
     * caller must go half-duplex to avoid the speaker feeding back into the mic.
     */
    @Volatile var aecActive: Boolean = false
        private set

    /** Neural (Silero) or energy-fallback end-pointing; state reset per capture. */
    private val detector: SpeechSegmenter by lazy { SpeechDetectorFactory.create(context) }

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** True if RECORD_AUDIO is granted (the overlay/service requests it elsewhere). */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Begin capturing mic audio into an in-memory PCM buffer. Idempotent.
     * [onAmplitude] is invoked with a normalized 0..1 RMS level per buffer so the caller
     * can animate a waveform (this path doesn't go through VoiceCaptureController, which
     * is what normally drives the overlay's amplitude). [onSpeechStart] fires once when
     * sustained speech begins (the barge-in hook); [onSpeechEnd] fires when trailing
     * silence ends the turn (VAD auto-submit).
     */
    fun start(
        onAmplitude: (Float) -> Unit = {},
        onSpeechStart: () -> Unit = {},
        onSpeechEnd: () -> Unit = {},
    ) {
        if (capturing) return
        if (!hasMicPermission()) {
            Log.e(TAG, "RECORD_AUDIO not granted — cannot start Whisper capture")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        // Small ~100 ms read chunks → the waveform updates ~10×/s (a 1 s buffer made it
        // look frozen) and voice-activity detection is responsive. The AudioRecord ring
        // buffer itself is kept larger so nothing drops between reads.
        val chunkBytes = SAMPLE_RATE * 2 * READ_CHUNK_MS / 1000
        // VOICE_COMMUNICATION routes through the platform's echo-cancelling
        // pre-processing chain; with the explicit AEC effect attached the mic
        // stream no longer hears the phone's own TTS — the prerequisite for
        // barge-in listening while the assistant is speaking.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuf, chunkBytes * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            runCatching { record.release() }
            return
        }
        aecActive = false
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                val canceller = AcousticEchoCanceler.create(record.audioSessionId)
                if (canceller != null) {
                    canceller.enabled = true
                    aecActive = true
                }
            }.onFailure { Log.w(TAG, "AEC attach failed: ${it.message}") }
        }
        Log.i(TAG, if (aecActive) "✅ AEC attached — full-duplex barge-in safe" else "⚠️ no AEC — caller should go half-duplex")
        audioRecord = record
        pcm.reset()
        capturing = true
        record.startRecording()
        Log.i(TAG, "🎙️ Whisper capture started (16 kHz mono, ${READ_CHUNK_MS}ms chunks)")
        detector.reset()
        captureJob = scope.launch {
            val buf = ByteArray(chunkBytes)
            var totalMs = 0L
            var spoke = false
            while (capturing) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) { Log.w(TAG, "AudioRecord.read returned $n"); continue }
                synchronized(pcm) { pcm.write(buf, 0, n) }
                onAmplitude(rmsAmplitude(buf, n))
                totalMs += (n / (SAMPLE_RATE * 2.0 / 1000)).toLong()
                for (event in detector.acceptPcm(buf, n)) when (event) {
                    SpeechEvent.SpeechStart -> {
                        spoke = true
                        // Barge-in capture starts when TTS starts, so the buffer
                        // may hold seconds of (echo-cancelled) lead-in. Keep a
                        // short pre-roll so Whisper gets clean context, not junk.
                        trimBufferToTail(PRE_ROLL_MS)
                        scope.launch(Dispatchers.Main) { onSpeechStart() }
                    }
                    SpeechEvent.SpeechEnd -> {
                        Log.i(TAG, "🔇 VAD end-of-speech → auto-stop")
                        capturing = false
                        scope.launch(Dispatchers.Main) { onSpeechEnd() }
                    }
                }
                if (capturing && totalMs >= MAX_CAPTURE_MS) {
                    Log.i(TAG, "⏱️ max capture reached → auto-stop")
                    capturing = false
                    if (spoke) scope.launch(Dispatchers.Main) { onSpeechEnd() }
                }
            }
        }
    }

    /** Drop all but the last [keepMs] of buffered PCM (pre-roll before speech). */
    private fun trimBufferToTail(keepMs: Int) {
        val keepBytes = SAMPLE_RATE * 2 * keepMs / 1000
        synchronized(pcm) {
            if (pcm.size() > keepBytes) {
                val tail = pcm.toByteArray().takeLast(keepBytes).toByteArray()
                pcm.reset()
                pcm.write(tail)
            }
        }
    }

    /** Normalized 0..1 RMS level over [n] bytes of little-endian 16-bit PCM. */
    private fun rmsAmplitude(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            sum += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (Math.sqrt(sum / count).toFloat() / 32767f).coerceIn(0f, 1f)
    }

    /**
     * Stop capture and transcribe via Groq Whisper. [language] is an optional ISO-639-1
     * hint (e.g. "en", "hi") that improves accuracy; null lets Whisper auto-detect.
     */
    suspend fun stopAndTranscribe(apiKey: String, language: String? = null): String {
        stopCapture()
        val pcmBytes = synchronized(pcm) { pcm.toByteArray() }
        if (pcmBytes.size < SAMPLE_RATE) { // < ~0.5 s of audio
            error("Didn't catch that — hold the mic a little longer.")
        }
        val wav = pcmToWav(pcmBytes)
        return withContext(Dispatchers.IO) { transcribe(wav, apiKey, language) }
    }

    /** Abort an in-progress capture without transcribing. */
    fun cancel() {
        stopCapture()
        synchronized(pcm) { pcm.reset() }
    }

    private fun stopCapture() {
        capturing = false
        runCatching { captureJob?.cancel() }
        captureJob = null
        audioRecord?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
        }
        audioRecord = null
    }

    private fun transcribe(wav: ByteArray, apiKey: String, language: String?): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", WHISPER_MODEL)
            .addFormDataPart("response_format", "text")
            .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
            .addFormDataPart(
                "file", "speech.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url(GROQ_TRANSCRIBE_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Whisper HTTP ${resp.code}: ${text.take(200)}")
                error("Transcription failed (${resp.code}).")
            }
            // response_format=text returns the raw transcript; tolerate a JSON body too.
            val transcript = if (text.trimStart().startsWith("{")) {
                runCatching { JSONObject(text).optString("text", "") }.getOrDefault("")
            } else {
                text
            }.trim()
            Log.i(TAG, "📝 Whisper transcript: ${transcript.take(80)}")
            return transcript
        }
    }

    /** Wrap raw 16-bit mono PCM in a 44-byte WAV (RIFF) header. */
    private fun pcmToWav(data: ByteArray): ByteArray {
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2
        val totalDataLen = 36 + data.size
        val header = ByteArray(44)
        fun putLe32(o: Int, v: Int) {
            header[o] = (v and 0xff).toByte()
            header[o + 1] = ((v shr 8) and 0xff).toByte()
            header[o + 2] = ((v shr 16) and 0xff).toByte()
            header[o + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putLe16(o: Int, v: Int) {
            header[o] = (v and 0xff).toByte()
            header[o + 1] = ((v shr 8) and 0xff).toByte()
        }
        "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        putLe32(4, totalDataLen)
        "WAVE".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> header[12 + i] = c.code.toByte() }
        putLe32(16, 16)          // PCM fmt chunk size
        putLe16(20, 1)           // audio format = PCM
        putLe16(22, channels)
        putLe32(24, SAMPLE_RATE)
        putLe32(28, byteRate)
        putLe16(32, channels * 2) // block align
        putLe16(34, 16)           // bits per sample
        "data".forEachIndexed { i, c -> header[36 + i] = c.code.toByte() }
        putLe32(40, data.size)
        return header + data
    }

    companion object {
        private const val TAG = "WhisperStt"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val WHISPER_MODEL = "whisper-large-v3-turbo"
        private const val GROQ_TRANSCRIBE_URL =
            "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val READ_CHUNK_MS = 100        // ~10 amplitude updates/sec
        private const val PRE_ROLL_MS = 400          // audio kept before detected speech
        private const val MAX_CAPTURE_MS = 30_000L   // safety cap (Groq free tier 25 MB)
    }
}
