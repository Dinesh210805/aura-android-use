package com.aura.aura_ui.agent.voice.vad

/** Per-frame speech-probability source. Frame = 512 samples of 16 kHz mono, -1..1. */
interface FrameScorer {
    /** Probability 0..1 that [frame] contains speech. */
    fun score(frame: FloatArray): Float

    /** Clear internal recurrent state (start of a fresh capture). */
    fun reset()
}

/** Boundary events emitted by [SpeechSegmenter]. */
sealed interface SpeechEvent {
    /** Sustained speech began (after minSpeechMs of consecutive speech frames). */
    data object SpeechStart : SpeechEvent

    /** Trailing silence exceeded silenceEndMs after speech — the turn is over. */
    data object SpeechEnd : SpeechEvent
}

/**
 * Hysteresis thresholds in probability space + debounce windows in wall time.
 * Defaults tuned for Silero (neural). The energy fallback overrides
 * silenceEndMs to the legacy 1500 ms because RMS end-pointing is sloppy.
 */
data class SegmenterConfig(
    val startThreshold: Float = 0.5f,
    val endThreshold: Float = 0.35f,
    val minSpeechMs: Int = 96,
    val silenceEndMs: Int = 700,
)

/**
 * Turns a stream of arbitrary-sized 16-bit LE mono PCM chunks into
 * [SpeechEvent]s. Buffers internally to fixed 512-sample frames (32 ms @
 * 16 kHz), scores each with [scorer], and applies start/end hysteresis.
 * Not thread-safe — call from the single capture loop.
 */
class SpeechSegmenter(
    private val scorer: FrameScorer,
    private val config: SegmenterConfig = SegmenterConfig(),
) {
    private val frame = FloatArray(FRAME_SAMPLES)
    private var frameFill = 0
    private var pendingLow = 0 // held low byte of a split 16-bit sample
    private var hasPendingLow = false

    private var inSpeech = false
    private var speechRunMs = 0
    private var silenceRunMs = 0

    fun reset() {
        frameFill = 0
        hasPendingLow = false
        inSpeech = false
        speechRunMs = 0
        silenceRunMs = 0
        scorer.reset()
    }

    /** Feed [n] valid bytes of [buf]; returns boundary events crossed, in order. */
    fun acceptPcm(buf: ByteArray, n: Int): List<SpeechEvent> {
        val events = mutableListOf<SpeechEvent>()
        var i = 0
        while (i < n) {
            val lo: Int
            val hi: Int
            if (hasPendingLow) {
                lo = pendingLow
                hi = buf[i].toInt()
                i += 1
                hasPendingLow = false
            } else if (i + 1 < n) {
                lo = buf[i].toInt() and 0xff
                hi = buf[i + 1].toInt()
                i += 2
            } else {
                pendingLow = buf[i].toInt() and 0xff
                hasPendingLow = true
                break
            }
            frame[frameFill++] = ((hi shl 8) or lo).toShort().toInt() / 32768f
            if (frameFill == FRAME_SAMPLES) {
                frameFill = 0
                onFrameScored(scorer.score(frame), events)
            }
        }
        return events
    }

    private fun onFrameScored(p: Float, events: MutableList<SpeechEvent>) {
        if (!inSpeech) {
            if (p >= config.startThreshold) {
                speechRunMs += FRAME_MS
                if (speechRunMs >= config.minSpeechMs) {
                    inSpeech = true
                    silenceRunMs = 0
                    events += SpeechEvent.SpeechStart
                }
            } else {
                speechRunMs = 0
            }
        } else {
            if (p < config.endThreshold) {
                silenceRunMs += FRAME_MS
                if (silenceRunMs >= config.silenceEndMs) {
                    inSpeech = false
                    speechRunMs = 0
                    silenceRunMs = 0
                    events += SpeechEvent.SpeechEnd
                }
            } else {
                silenceRunMs = 0
            }
        }
    }

    companion object {
        const val FRAME_SAMPLES = 512 // Silero v5 fixed chunk @ 16 kHz
        const val FRAME_MS = 32
    }
}
