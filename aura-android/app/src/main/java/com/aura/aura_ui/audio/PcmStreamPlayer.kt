package com.aura.aura_ui.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Streaming PCM audio player for Gemini Live audio responses.
 *
 * Uses AudioTrack.MODE_STREAM so chunks can be written incrementally as they
 * arrive from the WebSocket — no need to buffer the entire clip before playback.
 *
 * Gemini Live 2.0 outputs 24 kHz mono 16-bit little-endian PCM.
 *
 * Thread-safety: start() and stop() are @Synchronized. writeChunk() captures
 * a local reference to audioTrack so it is safe to call concurrently with stop().
 */
class PcmStreamPlayer {

    companion object {
        private const val TAG = "PcmStreamPlayer"

        /** Gemini Live 2.0 audio output format. */
        const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    }

    @Volatile
    private var audioTrack: AudioTrack? = null

    val isPlaying: Boolean get() = audioTrack != null

    /**
     * Seconds of this turn's audio the user has actually HEARD.
     *
     * Absolute, not a fraction — and that distinction was the bug. A played/written ratio
     * is meaningless early in a turn: audio arrives in chunks, so after the first chunk
     * "written" is tiny and "played" has nearly caught up with it, giving ~1.0 while most
     * of the sentence has not been generated yet. A caption paced on that ratio dumps the
     * whole text in the first moment and then sits still.
     *
     * Frames played over the sample rate has no such failure — it only ever counts audio
     * that genuinely came out of the speaker, and it cannot run ahead of itself.
     *
     * `getPlaybackHeadPosition()` is an unsigned 32-bit frame count in an Int; it wraps
     * after ~50 hours of continuous play and resets on flush(), which the interrupt path
     * calls. Returns 0 when nothing is playing.
     */
    fun playedSeconds(): Float {
        val track = audioTrack ?: return 0f
        val played = runCatching { track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL }
            .getOrDefault(0L)
        return played.toFloat() / SAMPLE_RATE
    }

    /**
     * Allocate and start the AudioTrack. Safe to call multiple times —
     * calling [start] while already playing is a no-op.
     * Thread-safe: synchronized on this instance.
     */
    @Synchronized
    fun start() {
        if (audioTrack != null) return


        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer == AudioTrack.ERROR || minBuffer == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioTrack.getMinBufferSize failed: $minBuffer")
            return
        }
        // 2× minBuffer: enough jitter headroom, but every byte in this buffer is speech already
        // COMMITTED to the speaker at barge-in time — a big buffer is a long "keeps talking over
        // me" tail. (Was 4×/16 KB ≈ 340 ms+ of committed tail.)
        val bufferSize = maxOf(minBuffer * 2, 8192)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Normally USAGE_VOICE_COMMUNICATION, so the hardware AEC receives this
                        // stream as its far-end reference — otherwise the model's own voice
                        // re-enters the mic uncancelled and server VAD can't hear the user barge
                        // in. Loudspeaker routing (the old reason for USAGE_MEDIA) is now done
                        // properly by the controller via setCommunicationDevice/speakerphone in
                        // comm mode, so this does NOT fall back to the earpiece.
                        //
                        // The developer recordable-voice switch flips this to USAGE_MEDIA, which
                        // is the only way a screen recorder can capture it — see
                        // RecordableVoiceStore for the AEC trade that buys.
                        .setUsage(RecordableVoiceStore.audioUsage())
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            if (RecordableVoiceStore.isRecordable) {
                Log.w(TAG, "recordable-voice mode: playing as USAGE_MEDIA — screen recorders can capture, barge-in degrades")
            }
            Log.d(TAG, "PcmStreamPlayer started: ${SAMPLE_RATE} Hz mono 16-bit, buffer=$bufferSize bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack: ${e.message}", e)
            audioTrack = null
        }
    }

    /**
     * Write raw PCM bytes to the AudioTrack.
     * Call this from a background thread (write() blocks while the buffer fills up).
     *
     * Captures audioTrack reference locally so concurrent stop() calls are safe.
     *
     * @param pcmBytes raw 24 kHz mono 16-bit little-endian PCM data.
     */
    fun writeChunk(pcmBytes: ByteArray) {
        val track = audioTrack ?: return // safe: @Volatile read

        var offset = 0
        while (offset < pcmBytes.size) {
            // A barge-in can release this track from another thread while we are blocked in
            // write(); writing to a released track throws. Treat any failure as "this turn's
            // audio is gone" and drop the rest of the chunk rather than killing the drain job.
            val written = try {
                track.write(pcmBytes, offset, pcmBytes.size - offset)
            } catch (e: IllegalStateException) {
                Log.d(TAG, "write on released track (barge-in): ${e.message}")
                return
            }
            when {
                written > 0 -> offset += written
                written == AudioTrack.ERROR_INVALID_OPERATION -> {
                    Log.w(TAG, "AudioTrack.write: INVALID_OPERATION — track stopped?")
                    break
                }
                else -> break
            }
            // Interrupted mid-chunk: stop feeding the dead turn immediately.
            if (audioTrack !== track) return
        }
    }

    /**
     * Immediately silence and release the AudioTrack — the barge-in path.
     *
     * [stop] is NOT immediate: AudioTrack.stop() on a MODE_STREAM track plays out everything
     * already written, and at interrupt time the buffer is essentially always full (the model
     * streams faster than realtime, so write() blocks on a full buffer). pause()+flush() is the
     * documented immediate-halt sequence; release() then frees the track.
     */
    @Synchronized
    fun interrupt() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            track.pause()
            track.flush()
            track.release()
            Log.d(TAG, "PcmStreamPlayer interrupted (pause+flush)")
        } catch (e: Exception) {
            Log.w(TAG, "Error interrupting PcmStreamPlayer: ${e.message}")
        }
    }

    /**
     * Stop playback and release the AudioTrack.
     * Safe to call even if already stopped.
     * Thread-safe: synchronized on this instance.
     * NOTE: lets already-written audio play out (graceful end-of-turn). For an
     * immediate cut (barge-in) use [interrupt].
     */
    @Synchronized
    fun stop() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            track.stop()
            track.flush()
            track.release()
            Log.d(TAG, "PcmStreamPlayer stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping PcmStreamPlayer: ${e.message}")
        }
    }
}
