package com.aura.aura_ui.voice

import com.aura.aura_ui.agent.voice.vad.FrameScorer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RecentSpeech]: a wake word hit needs speech in the last ~1.5 s. */
class RecentSpeechTest {

    /** Scores each frame from a script: 1 = speech, 0 = not. */
    private class ScriptedScorer(vararg probs: Float) : FrameScorer {
        private val queue = ArrayDeque(probs.toList())
        override fun score(frame: FloatArray) = queue.removeFirstOrNull() ?: 0f
        override fun reset() = Unit
    }

    private fun frames(n: Int) = ShortArray(512 * n)

    @Test
    fun `noise only is never speech`() {
        val gate = RecentSpeech(ScriptedScorer(0f, 0f, 0f))
        gate.accept(frames(3), 512 * 3)
        assertFalse(gate.heard)
    }

    @Test
    fun `speech is remembered for the hold, then forgotten`() {
        val gate = RecentSpeech(ScriptedScorer(1f), holdFrames = 2)
        gate.accept(frames(3), 512 * 3) // speech, then 2 silent frames
        assertTrue(gate.heard)
        gate.accept(frames(1), 512)
        assertFalse(gate.heard)
    }

    @Test
    fun `chunks not a multiple of 512 still form frames`() {
        val gate = RecentSpeech(ScriptedScorer(1f))
        gate.accept(ShortArray(1280), 1280) // 2 full frames + 256 left over
        assertTrue(gate.heard)
    }
}
