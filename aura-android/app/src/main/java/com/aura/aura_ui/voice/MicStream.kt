package com.aura.aura_ui.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.util.Log
import com.aura.aura_ui.agent.voice.vad.SpeechDetectorFactory
import com.aura.aura_ui.agent.voice.vad.SpeechEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * The microphone for a voice conversation: 16 kHz mono PCM with hardware echo cancellation, and
 * Silero's speech/not-speech decision on every chunk.
 *
 * - Contract: [start] is idempotent; [onChunk] runs on one IO thread, in order, with the events
 *   Silero found in that chunk. [stop] ends capture. Echo cancellation needs AURA's own speech to
 *   play on the voice-communication path; [CommAudio.enter] sets that up.
 * - Fails: no permission or a refused recorder logs and leaves [running] false.
 */
class MicStream(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onChunk: (pcm: ByteArray, events: List<SpeechEvent>, level: Float) -> Unit,
) {
    private val segmenter by lazy { SpeechDetectorFactory.create(context) }
    private var job: Job? = null

    @Volatile
    var running = false
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val chunkBytes = RATE * 2 * CHUNK_MS / 1000
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunkBytes * 4),
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone refused")
            record?.release()
            return
        }
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true }
        runCatching { record.startRecording() }.onFailure {
            Log.w(TAG, "startRecording failed: ${it.message}")
            record.release()
            return
        }
        running = true
        segmenter.reset()
        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(chunkBytes)
            try {
                while (isActive && running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val events = segmenter.acceptPcm(buf, n)
                    onChunk(buf.copyOf(n), events, level(buf, n))
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "MicStream"
        const val RATE = 16_000
        const val CHUNK_MS = 40

        /** RMS as a fraction of full scale. */
        fun level(buf: ByteArray, n: Int): Float {
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < n) {
                val s = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toDouble()
                sum += s * s
                count++
                i += 2
            }
            return if (count == 0) 0f else (sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
        }
    }
}

/**
 * Loudspeaker in communication mode, which arms the phone's echo path: AURA's voice, played as
 * voice-communication audio, becomes the reference the echo canceller removes from the mic. That
 * is what lets the person talk over AURA and be heard.
 *
 * - Contract: [enter] and [leave] are idempotent and process-wide; only one voice conversation
 *   runs at a time.
 */
object CommAudio {
    @Volatile
    private var entered = false

    fun enter(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.takeIf { am.communicationDevice?.id != it.id }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
            entered = true
        }.onFailure { Log.w("CommAudio", "routing failed: ${it.message}") }
    }

    fun leave(context: Context) {
        if (!entered) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice() else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        }
        entered = false
    }
}
