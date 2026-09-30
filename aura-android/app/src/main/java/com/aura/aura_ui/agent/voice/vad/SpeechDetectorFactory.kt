package com.aura.aura_ui.agent.voice.vad

import android.content.Context
import android.util.Log

/**
 * Builds the app's speech detector: Silero VAD when the model loads, otherwise
 * the RMS [EnergyScorer] with the legacy 1500 ms end-pointing window. Context is
 * nullable only for JVM tests that inject a fake scorer.
 */
object SpeechDetectorFactory {

    fun create(
        context: Context?,
        neuralScorerProvider: (Context?) -> FrameScorer = {
            SileroVadScorer(requireNotNull(it) { "context required for neural VAD" })
        },
    ): SpeechSegmenter = try {
        SpeechSegmenter(neuralScorerProvider(context), SegmenterConfig())
    } catch (t: Throwable) {
        Log.w(TAG, "Neural VAD unavailable (${t.message}) — falling back to energy VAD")
        SpeechSegmenter(EnergyScorer(), EnergyScorer.LEGACY_CONFIG)
    }

    private const val TAG = "SpeechDetectorFactory"
}
