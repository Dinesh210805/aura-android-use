package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The barge-in tail regression: after the server reports `interrupted`, the cancelled turn's
 * audio frames are already in flight and land a few milliseconds later. Without a gate they
 * restart the drain and the assistant audibly keeps talking after being cut off.
 */
class InterruptGateTest {

    @Test fun `passes audio when nothing was ever interrupted`() {
        val gate = InterruptGate()
        assertFalse(gate.shouldDrop(0L))
        assertFalse(gate.shouldDrop(10_000L))
    }

    @Test fun `drops the in-flight tail immediately after an interrupt`() {
        val gate = InterruptGate(graceMs = 350L)
        gate.onInterrupted(1_000L)
        assertTrue("frame arriving with the interrupt must be dropped", gate.shouldDrop(1_000L))
        assertTrue("frame 100 ms later is still the dead turn", gate.shouldDrop(1_100L))
        assertTrue(gate.shouldDrop(1_349L))
    }

    @Test fun `reopens once the grace window elapses so the next reply is audible`() {
        val gate = InterruptGate(graceMs = 350L)
        gate.onInterrupted(1_000L)
        assertFalse("grace boundary must reopen the gate", gate.shouldDrop(1_350L))
        assertFalse(gate.shouldDrop(2_000L))
    }

    @Test fun `a second interrupt extends the window from the new interrupt`() {
        val gate = InterruptGate(graceMs = 350L)
        gate.onInterrupted(1_000L)
        gate.onInterrupted(1_300L)
        assertTrue("window restarts at the later interrupt", gate.shouldDrop(1_500L))
        assertFalse(gate.shouldDrop(1_650L))
    }

    @Test fun `reset reopens the gate for a fresh session`() {
        val gate = InterruptGate(graceMs = 350L)
        gate.onInterrupted(1_000L)
        gate.reset()
        assertFalse(gate.shouldDrop(1_010L))
    }

    @Test fun `a local barge-in mutes the dead turn for much longer than a server interrupt`() {
        // The server has NOT been told about a local (on-device VAD) barge-in, so it keeps
        // streaming the cancelled turn until it hears the user — the hold must outlast that.
        val gate = InterruptGate()
        gate.onLocalBargeIn(1_000L)
        assertTrue(gate.shouldDrop(1_500L))
        assertTrue("must still be muted well past the server-interrupt grace", gate.shouldDrop(2_500L))
        assertFalse(gate.shouldDrop(3_000L))
    }

    @Test fun `generationComplete reopens the gate early so the next reply is not clipped`() {
        val gate = InterruptGate()
        gate.onLocalBargeIn(1_000L)
        assertTrue(gate.shouldDrop(1_200L))
        gate.clear() // the killed turn's generationComplete landed
        assertFalse("next reply must be audible immediately", gate.shouldDrop(1_201L))
    }

    @Test fun `local barge-in hold is a backstop, longer than the server-interrupt grace`() {
        assertTrue(InterruptGate.LOCAL_BARGE_IN_HOLD_MS > InterruptGate.DEFAULT_GRACE_MS)
    }

    @Test fun `default grace covers a realistic in-flight tail but not the next reply`() {
        // The server only replies after silenceDurationMs (600 ms) of user silence, so a grace
        // window comfortably under that can never clip the genuine next turn.
        assertTrue(InterruptGate.DEFAULT_GRACE_MS in 200L..500L)
        assertTrue(InterruptGate.DEFAULT_GRACE_MS < CompanionConfig.SILENCE_DURATION_MS)
    }
}
