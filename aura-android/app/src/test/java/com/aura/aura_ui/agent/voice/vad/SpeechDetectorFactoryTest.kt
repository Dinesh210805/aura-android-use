package com.aura.aura_ui.agent.voice.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * onnxruntime-android can't run on the JVM, so these tests cover the factory's
 * fallback contract with injected scorers; Silero inference itself is verified
 * in the on-device smoke (plan Task 6).
 */
class SpeechDetectorFactoryTest {

    private class FakeNeural : FrameScorer {
        override fun score(frame: FloatArray) = 0.9f
        override fun reset() = Unit
    }

    @Test
    fun `uses neural scorer when provider succeeds`() {
        val seg = SpeechDetectorFactory.create(context = null, neuralScorerProvider = { FakeNeural() })
        assertNotNull(seg)
        // Neural config: SpeechStart after 96 ms (3 frames) of sustained speech.
        val speech = ByteArray(SpeechSegmenter.FRAME_SAMPLES * 2)
        val events = mutableListOf<SpeechEvent>()
        repeat(4) { events += seg.acceptPcm(speech, speech.size) }
        assertEquals(listOf<SpeechEvent>(SpeechEvent.SpeechStart), events)
    }

    @Test
    fun `falls back to EnergyScorer with legacy config when provider throws`() {
        val seg = SpeechDetectorFactory.create(
            context = null,
            neuralScorerProvider = { error("model missing") },
        )
        assertNotNull(seg)
        // Legacy config end window is 1500 ms — feed loud frames then silence and
        // confirm SpeechEnd needs ≥ 47 silence frames (1500/32), not 22 (700/32).
        val loud = ByteArray(SpeechSegmenter.FRAME_SAMPLES * 2)
        for (i in loud.indices step 2) {
            loud[i] = 0x00
            loud[i + 1] = 0x30 // sample 0x3000 → ~0.37 amplitude, well above 0.06 RMS
        }
        val silent = ByteArray(SpeechSegmenter.FRAME_SAMPLES * 2)
        val events = mutableListOf<SpeechEvent>()
        repeat(4) { events += seg.acceptPcm(loud, loud.size) }
        repeat(30) { events += seg.acceptPcm(silent, silent.size) } // 960 ms < 1500 ms
        assertEquals(listOf<SpeechEvent>(SpeechEvent.SpeechStart), events)
        repeat(20) { events += seg.acceptPcm(silent, silent.size) } // now > 1500 ms total
        assertEquals(listOf(SpeechEvent.SpeechStart, SpeechEvent.SpeechEnd), events)
    }
}
