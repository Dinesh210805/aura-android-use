package com.aura.aura_ui.data.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Manages audio capture and provides amplitude analysis
 */
@Singleton
class AudioCaptureManager
    @Inject
    constructor() {
        companion object {
            private const val SAMPLE_RATE = 44100
            private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
            private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
            private const val BUFFER_SIZE_MULTIPLIER = 2
        }

        private var audioRecord: AudioRecord? = null
        private var isRecording = false
        private val bufferSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
            ) * BUFFER_SIZE_MULTIPLIER

        init {
            AgentLogger.Audio.d(
                "AudioCaptureManager initialized",
                mapOf(
                    "sampleRate" to SAMPLE_RATE,
                    "bufferSize" to bufferSize,
                ),
            )
        }

        /**
         * Start audio capture and return flow of audio data
         */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun startCapture(): Flow<ByteArray> =
            flow {
                AgentLogger.Audio.i("Starting audio capture")
                try {
                    initializeAudioRecord()
                    audioRecord?.startRecording()
                    isRecording = true

                    val buffer = ByteArray(bufferSize)

                    while (coroutineContext.isActive && isRecording) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (bytesRead > 0) {
                            emit(buffer.copyOf(bytesRead))
                        }
                    }
                } catch (e: Exception) {
                    AgentLogger.Audio.e("Failed to capture audio", e)
                    throw AudioCaptureException("Failed to capture audio", e)
                } finally {
                    stopCaptureInternal()
                    AgentLogger.Audio.d("Audio capture flow completed")
                }
            }

        /**
         * Get real-time amplitude for animations
         */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun getAmplitudeFlow(): Flow<Float> =
            flow {
                AgentLogger.Audio.i("Starting amplitude monitoring")
                try {
                    initializeAudioRecord()
                    audioRecord?.startRecording()
                    isRecording = true

                    val buffer = ShortArray(bufferSize / 2) // 16-bit samples

                    while (coroutineContext.isActive && isRecording) {
                        val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (samplesRead > 0) {
                            val amplitude = calculateAmplitude(buffer, samplesRead)
                            emit(amplitude)
                        }
                    }
                } catch (e: Exception) {
                    AgentLogger.Audio.e("Failed to capture amplitude", e)
                    throw AudioCaptureException("Failed to capture amplitude", e)
                } finally {
                    stopCaptureInternal()
                    AgentLogger.Audio.d("Amplitude monitoring completed")
                }
            }

        /**
         * Stop audio capture
         */
        fun stopCapture() {
            AgentLogger.Audio.i("Stopping audio capture")
            isRecording = false
            stopCaptureInternal()
        }

        private fun initializeAudioRecord() {
            if (audioRecord == null) {
                audioRecord =
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize,
                    )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw AudioCaptureException("AudioRecord not initialized properly")
            }
        }

        private fun stopCaptureInternal() {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
            audioRecord = null
            isRecording = false
        }

        private fun calculateAmplitude(
            buffer: ShortArray,
            samplesRead: Int,
        ): Float {
            if (samplesRead == 0) return 0f

            var sum = 0.0
            for (i in 0 until samplesRead) {
                sum += (buffer[i] * buffer[i]).toDouble()
            }

            val rms = sqrt(sum / samplesRead)

            // Normalize to 0-1 range (adjust based on typical mic input levels)
            return (rms.toFloat() / 32767f).coerceIn(0f, 1f)
        }
    }

/**
 * Exception for audio capture errors
 */
class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)
