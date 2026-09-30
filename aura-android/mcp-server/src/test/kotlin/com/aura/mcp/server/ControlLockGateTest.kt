package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Spec 2026-07-31 — the seam that reads "has the human taken the wheel?" at dispatch time.
 *
 * Pure decision logic lives in [ControlLock]; this class only supplies the bit.
 */
class ControlLockGateTest {

    @Test
    fun `a paused device refuses the call`() {
        val gate = ControlLockGate { true }

        assertNotNull(gate.check())
    }

    @Test
    fun `an unpaused device does not interfere`() {
        val gate = ControlLockGate { false }

        assertNull(gate.check())
    }

    @Test
    fun `resuming makes the very next call work`() {
        // The spec's stated escape hatch is exactly this — no re-pairing, no reconnect,
        // no special resume call. The client's next attempt simply succeeds.
        var paused = true
        val gate = ControlLockGate { paused }
        assertNotNull(gate.check())

        paused = false

        assertNull(gate.check(), "the lock must release the moment the human hands it back")
    }

    @Test
    fun `the gate is read fresh on every call and never cached`() {
        // A cached verdict would mean the pause lands one tool call late — the agent gets
        // one more free action on a phone its user is already holding.
        var reads = 0
        val gate = ControlLockGate { reads++; false }

        gate.check()
        gate.check()

        assertEquals(2, reads)
    }

    @Test
    fun `a broken pause source fails OPEN`() {
        // Deliberate, and it matches SensitivePolicy/OPA/Prompt-Guard posture elsewhere in
        // this codebase: a store that cannot answer must not be able to brick every tool
        // call on the device. Losing pause is bad; losing the whole product is worse.
        val gate = ControlLockGate { error("pause store unavailable") }

        assertNull(gate.check())
    }

    @Test
    fun `NOOP never blocks`() {
        // Unit tests and non-device hosts have no human to yield to.
        assertNull(ControlLockGate.NOOP.check())
    }
}
