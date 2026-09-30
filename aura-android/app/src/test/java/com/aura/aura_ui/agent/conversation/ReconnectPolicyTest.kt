package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun `first drop reconnects immediately - server resets are routine`() {
        assertEquals(0L, ReconnectPolicy.delayForMs(0))
    }

    @Test fun `repeated drops back off exponentially and cap`() {
        assertEquals(500L, ReconnectPolicy.delayForMs(1))
        assertEquals(1_000L, ReconnectPolicy.delayForMs(2))
        assertEquals(2_000L, ReconnectPolicy.delayForMs(3))
        assertEquals(4_000L, ReconnectPolicy.delayForMs(4))
        assertEquals(8_000L, ReconnectPolicy.delayForMs(5))
        assertEquals(8_000L, ReconnectPolicy.delayForMs(20))   // capped, never overflows
    }

    @Test fun `gives up after the max consecutive drops, not before`() {
        assertFalse(ReconnectPolicy.shouldGiveUp(ReconnectPolicy.MAX_CONSECUTIVE_DROPS - 1))
        assertTrue(ReconnectPolicy.shouldGiveUp(ReconnectPolicy.MAX_CONSECUTIVE_DROPS))
        assertTrue(ReconnectPolicy.shouldGiveUp(ReconnectPolicy.MAX_CONSECUTIVE_DROPS + 3))
    }
}
