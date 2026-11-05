package com.aura.aura_ui.agent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.aura.aura_ui.agent.voice.vad.SpeechDetectorFactory
import com.aura.aura_ui.agent.voice.vad.SpeechEvent
import com.aura.aura_ui.agent.voice.vad.SpeechSegmenter
import com.aura.aura_ui.voice.MoonshineStt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Push-to-talk / hands-free speech capture with on-device transcription (Moonshine). Nothing leaves the phone.
 *
 * - Contract: [start] captures 16 kHz mono PCM; [stopAndTranscribe] ends capture and returns the text;
 *   [cancel] aborts. `onSpeechStart` fires when speech begins, `onSpeechEnd` is the VAD auto-submit.
 * - Fails: [stopAndTranscribe] throws with a user-facing message on too-short audio or a missing model.
 *   Callers check [isModelReady] first and fall back to Android's on-device SpeechRecognizer.
 */
class LocalSttController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var capturing = false
    private val pcm = ByteArrayOutputStream()

    /** Neural (Silero) or energy-fallback end-pointing; state reset per capture. */
    private val detector: SpeechSegmenter by lazy { SpeechDetectorFactory.create(context) }

    /** True once the on-device speech model is downloaded. */
    fun isModelReady(): Boolean = MoonshineStt.isAvailable(context)

    /** True if RECORD_AUDIO is granted (the overlay/service requests it elsewhere). */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Begin capturing mic audio into an in-memory PCM buffer. Idempotent.
     * [onAmplitude] is invoked with a normalized 0..1 RMS level per buffer so the caller
     * can animate a waveform (this path doesn't go through VoiceCaptureController, which
     * is what normally drives the overlay's amplitude). [onSpeechStart] fires once when
     * sustained speech begins; [onSpeechEnd] fires when trailing
     * silence ends the turn (VAD auto-submit).
     */
    fun start(
        onAmplitude: (Float) -> Unit = {},
        onSpeechStart: () -> Unit = {},
        onSpeechEnd: () -> Unit = {},
    ) {
        if (capturing) return
        if (!hasMicPermission()) {
            Log.e(TAG, "RECORD_AUDIO not granted — cannot start capture")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        // Small ~100 ms read chunks → the waveform updates ~10×/s (a 1 s buffer made it
        // look frozen) and voice-activity detection is responsive. The AudioRecord ring
        // buffer itself is kept larger so nothing drops between reads.
        val chunkBytes = SAMPLE_RATE * 2 * READ_CHUNK_MS / 1000
        // VOICE_RECOGNITION: the platform's ASR tuning, without call-style processing. The mic is
        // only open while AURA is silent (half-duplex), so no echo cancellation is needed.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuf, chunkBytes * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            runCatching { record.release() }
            return
        }
        audioRecord = record
        pcm.reset()
        capturing = true
        record.startRecording()
        Log.i(TAG, "🎙️ capture started (16 kHz mono, ${READ_CHUNK_MS}ms chunks)")
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
                        // A hands-free capture can sit open for seconds before the user
                        // speaks; keep only a short pre-roll so the recognizer gets clean context.
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

    /** Stop capture and transcribe on-device. Blocking-heavy: runs on [Dispatchers.Default]. */
    suspend fun stopAndTranscribe(): String {
        stopCapture()
        val pcmBytes = synchronized(pcm) { pcm.toByteArray() }
        if (pcmBytes.size < SAMPLE_RATE) { // < ~0.5 s of audio
            error("Didn't catch that — hold the mic a little longer.")
        }
        return withContext(Dispatchers.Default) { MoonshineStt.transcribe(context, pcmBytes) }
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

    companion object {
        private const val TAG = "LocalStt"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_CHUNK_MS = 100        // ~10 amplitude updates/sec
        private const val PRE_ROLL_MS = 400          // audio kept before detected speech
        private const val MAX_CAPTURE_MS = 30_000L   // safety cap on one utterance
    }
}
