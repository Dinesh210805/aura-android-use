package com.aura.aura_ui.agent.voice.vad

import kotlin.math.sqrt

/**
 * Fallback scorer when the Silero model can't load: frame RMS against the
 * legacy WhisperSttController threshold (0.06). Binary output — the segmenter's
 * hysteresis still applies, but end-pointing stays sloppy, so pair with
 * [LEGACY_CONFIG] (1500 ms trailing silence, like the code this replaces).
 */
class EnergyScorer(private val threshold: Float = 0.06f) : FrameScorer {

    override fun score(frame: FloatArray): Float {
        var sum = 0.0
        for (s in frame) sum += (s * s).toDouble()
        val rms = sqrt(sum / frame.size).toFloat()
        return if (rms >= threshold) 1f else 0f
    }

    override fun reset() = Unit // stateless

    companion object {
        /** Segmenter windows matching the pre-VAD RMS behavior. */
        val LEGACY_CONFIG = SegmenterConfig(
            startThreshold = 0.5f,
            endThreshold = 0.35f,
            minSpeechMs = 96,
            silenceEndMs = 1500,
        )
    }
}
