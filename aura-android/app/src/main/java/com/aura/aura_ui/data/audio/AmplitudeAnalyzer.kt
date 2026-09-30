package com.aura.aura_ui.data.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyzes audio amplitude and provides smoothed values for animations
 */
@Singleton
class AmplitudeAnalyzer
    @Inject
    constructor() {
        private val _smoothedAmplitude = MutableStateFlow(0f)
        val smoothedAmplitude: StateFlow<Float> = _smoothedAmplitude.asStateFlow()

        private var previousAmplitude = 0f

        companion object {
            private const val SMOOTHING_FACTOR = 0.3f
            private const val DECAY_FACTOR = 0.95f
            private const val MIN_THRESHOLD = 0.01f
        }

        /**
         * Update amplitude with smoothing
         */
        fun updateAmplitude(rawAmplitude: Float) {
            // Apply exponential moving average for smoothing
            val smoothed =
                if (rawAmplitude > previousAmplitude) {
                    // Faster response for increasing amplitude
                    previousAmplitude + (rawAmplitude - previousAmplitude) * SMOOTHING_FACTOR * 2
                } else {
                    // Slower decay for decreasing amplitude
                    previousAmplitude + (rawAmplitude - previousAmplitude) * SMOOTHING_FACTOR
                }

            // Apply decay when amplitude is very low
            val finalAmplitude =
                if (smoothed < MIN_THRESHOLD) {
                    smoothed * DECAY_FACTOR
                } else {
                    smoothed
                }

            previousAmplitude = finalAmplitude.coerceIn(0f, 1f)
            _smoothedAmplitude.value = previousAmplitude
        }

        /**
         * Reset amplitude to zero
         */
        fun reset() {
            previousAmplitude = 0f
            _smoothedAmplitude.value = 0f
        }

        /**
         * Get current amplitude value
         */
        fun getCurrentAmplitude(): Float = _smoothedAmplitude.value

        /**
         * Map amplitude to visual scale (for animations)
         */
        fun getAnimationScale(amplitude: Float = getCurrentAmplitude()): Float {
            // Map 0-1 amplitude to 1.0-1.25 scale for visual feedback
            return 1.0f + (amplitude * 0.25f)
        }

        /**
         * Get waveform bar heights (for waveform visualization)
         */
        fun getWaveformHeights(
            amplitude: Float = getCurrentAmplitude(),
            barCount: Int = 12,
        ): FloatArray {
            val heights = FloatArray(barCount)

            if (amplitude < MIN_THRESHOLD) {
                // Static minimal heights when silent
                heights.fill(0.1f)
                return heights
            }

            // Generate varied heights with some randomness
            for (i in 0 until barCount) {
                val baseHeight = amplitude * (0.7f + Math.random().toFloat() * 0.3f)
                heights[i] = baseHeight.coerceIn(0.1f, 1.0f)
            }

            return heights
        }
    }
