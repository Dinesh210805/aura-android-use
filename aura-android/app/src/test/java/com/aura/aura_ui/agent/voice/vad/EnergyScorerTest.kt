package com.aura.aura_ui.agent.voice.vad

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class EnergyScorerTest {

    private fun tone(amplitude: Float): FloatArray =
        FloatArray(SpeechSegmenter.FRAME_SAMPLES) { i ->
            (amplitude * sin(2.0 * Math.PI * 440.0 * i / 16000.0)).toFloat()
        }

    @Test
    fun `loud frame scores 1`() {
        assertEquals(1f, EnergyScorer().score(tone(0.5f)), 0.0001f)
    }

    @Test
    fun `quiet frame scores 0`() {
        assertEquals(0f, EnergyScorer().score(tone(0.01f)), 0.0001f)
    }

    @Test
    fun `silence scores 0`() {
        assertEquals(0f, EnergyScorer().score(FloatArray(SpeechSegmenter.FRAME_SAMPLES)), 0.0001f)
    }

    @Test
    fun `legacy config keeps the 1500ms silence window`() {
        assertEquals(1500, EnergyScorer.LEGACY_CONFIG.silenceEndMs)
    }
}
