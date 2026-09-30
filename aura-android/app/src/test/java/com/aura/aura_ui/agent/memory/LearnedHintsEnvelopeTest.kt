package com.aura.aura_ui.agent.memory

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1: learned hints are derived from screen text (attacker-controllable) and must NOT be
 * concatenated into the system prompt with system authority. They travel in the user/goal
 * channel wrapped in an explicit untrusted-data envelope that tells the model they are
 * advisory and must never be treated as instructions.
 */
class LearnedHintsEnvelopeTest {

    @Test fun `blank hints produce no envelope`() {
        assertNull(LearnedHintsEnvelope.wrap(""))
        assertNull(LearnedHintsEnvelope.wrap("   "))
    }

    @Test fun `wrapped hints are fenced and marked untrusted advisory`() {
        val env = LearnedHintsEnvelope.wrap("• A path that worked before: tap som_id=3")!!
        assertTrue("must state the data is untrusted", env.lowercase().contains("untrusted"))
        assertTrue("must state it is advisory / not instructions", env.lowercase().contains("advisory") || env.lowercase().contains("not instructions"))
        assertTrue("must contain the hint body", env.contains("som_id=3"))
    }

    @Test fun `the envelope carries the goal so the model keeps it as the task`() {
        val env = LearnedHintsEnvelope.forGoal("play liked songs", "• hint body")!!
        assertTrue(env.contains("play liked songs"))
        assertTrue(env.contains("hint body"))
    }

    @Test fun `no hints returns the goal unchanged (null envelope path)`() {
        assertNull(LearnedHintsEnvelope.forGoal("play liked songs", null))
        assertNull(LearnedHintsEnvelope.forGoal("play liked songs", ""))
    }
}
