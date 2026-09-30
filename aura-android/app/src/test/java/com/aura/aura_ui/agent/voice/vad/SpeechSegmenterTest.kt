package com.aura.aura_ui.agent.voice.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feeds synthetic PCM through a fake scorer; frames are 512 samples (32 ms @16 kHz). */
class SpeechSegmenterTest {

    /** Scorer that replays a scripted probability sequence, one per frame. */
    private class ScriptedScorer(private val probs: List<Float>) : FrameScorer {
        private var i = 0
        override fun score(frame: FloatArray): Float = probs.getOrElse(i++) { 0f }
        override fun reset() { i = 0 }
    }

    private fun pcmFrames(count: Int): ByteArray = ByteArray(count * 512 * 2)

    private fun collect(seg: SpeechSegmenter, frames: Int): List<SpeechEvent> {
        val out = mutableListOf<SpeechEvent>()
        repeat(frames) { out += seg.acceptPcm(pcmFrames(1), 512 * 2) }
        return out
    }

    private val config = SegmenterConfig(
        startThreshold = 0.5f, endThreshold = 0.35f,
        minSpeechMs = 64, silenceEndMs = 96,
    ) // 64 ms = 2 frames of speech to start; 96 ms = 3 frames of silence to end

    @Test
    fun `silence produces no events`() {
        val seg = SpeechSegmenter(ScriptedScorer(List(10) { 0.1f }), config)
        assertTrue(collect(seg, 10).isEmpty())
    }

    @Test
    fun `sustained speech fires SpeechStart once`() {
        val seg = SpeechSegmenter(ScriptedScorer(List(10) { 0.9f }), config)
        val events = collect(seg, 10)
        assertEquals(listOf<SpeechEvent>(SpeechEvent.SpeechStart), events)
    }

    @Test
    fun `single noisy frame below minSpeechMs does not trigger start`() {
        val probs = listOf(0.9f) + List(9) { 0.1f } // 1 frame spike = 32 ms < 64 ms
        val seg = SpeechSegmenter(ScriptedScorer(probs), config)
        assertTrue(collect(seg, 10).isEmpty())
    }

    @Test
    fun `speech then trailing silence fires SpeechEnd`() {
        val probs = List(4) { 0.9f } + List(5) { 0.1f }
        val seg = SpeechSegmenter(ScriptedScorer(probs), config)
        val events = collect(seg, 9)
        assertEquals(listOf(SpeechEvent.SpeechStart, SpeechEvent.SpeechEnd), events)
    }

    @Test
    fun `brief dip below endThreshold does not end the turn`() {
        // 4 speech, 2 silence (64 ms < 96 ms), speech resumes → no SpeechEnd
        val probs = List(4) { 0.9f } + List(2) { 0.1f } + List(4) { 0.9f }
        val seg = SpeechSegmenter(ScriptedScorer(probs), config)
        val events = collect(seg, 10)
        assertEquals(listOf<SpeechEvent>(SpeechEvent.SpeechStart), events)
    }

    @Test
    fun `partial frames are buffered across acceptPcm calls`() {
        // 100 ms chunks (1600 samples = 3.125 frames) like WhisperSttController sends.
        val seg = SpeechSegmenter(ScriptedScorer(List(20) { 0.9f }), config)
        val out = mutableListOf<SpeechEvent>()
        repeat(4) { out += seg.acceptPcm(ByteArray(1600 * 2), 1600 * 2) }
        assertEquals(listOf<SpeechEvent>(SpeechEvent.SpeechStart), out)
    }

    @Test
    fun `reset clears state so a new turn can start`() {
        val probs = List(4) { 0.9f } + List(4) { 0.9f }
        val seg = SpeechSegmenter(ScriptedScorer(probs), config)
        collect(seg, 4)
        seg.reset()
        val events = collect(seg, 4)
        assertEquals(listOf<SpeechEvent>(SpeechEvent.SpeechStart), events)
    }
}
