package com.aura.aura_ui.services.keepawake

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ScreenAwakeControllerTest {

    private class FakeMechanism : ScreenAwakeMechanism {
        var engageCount = 0
        var disengageCount = 0
        override fun engage() { engageCount++ }
        override fun disengage() { disengageCount++ }
    }

    private val mechanism = FakeMechanism()
    private val controller = ScreenAwakeController(mechanism, Handler(Looper.getMainLooper()))

    @Test fun `first lease engages the mechanism exactly once`() {
        controller.acquire("run")
        assertEquals(1, mechanism.engageCount)
        assertTrue(controller.isHeld())
    }

    @Test fun `overlapping leases share one engagement - no re-engage on second acquire`() {
        controller.acquire("agent_run")
        controller.acquire("mcp_session")
        assertEquals(1, mechanism.engageCount)
    }

    @Test fun `screen releases only when the LAST lease closes`() {
        val agent = controller.acquire("agent_run")
        val mcp = controller.acquire("mcp_session")
        agent.close()
        assertEquals(0, mechanism.disengageCount) // MCP still needs the screen
        mcp.close()
        assertEquals(1, mechanism.disengageCount)
        assertFalse(controller.isHeld())
    }

    @Test fun `double close is a safe no-op`() {
        val lease = controller.acquire("run")
        lease.close()
        lease.close()
        assertEquals(1, mechanism.disengageCount)
    }

    @Test fun `close of an already-backstopped lease does not disengage a newer holder`() {
        val leaked = controller.acquire("leaked")
        shadowOf(Looper.getMainLooper()).idleFor(ScreenAwakeController.MAX_HOLD_MS, TimeUnit.MILLISECONDS)
        assertEquals(1, mechanism.disengageCount) // backstop reclaimed it
        val fresh = controller.acquire("fresh")
        leaked.close() // late close of the dead lease
        assertTrue(controller.isHeld())
        assertEquals(1, mechanism.disengageCount)
        fresh.close()
    }

    @Test fun `backstop reclaims a leaked lease so the screen cannot be pinned forever`() {
        controller.acquire("leaked") // never closed
        shadowOf(Looper.getMainLooper()).idleFor(ScreenAwakeController.MAX_HOLD_MS, TimeUnit.MILLISECONDS)
        assertEquals(1, mechanism.disengageCount)
        assertFalse(controller.isHeld())
    }

    @Test fun `mechanism failures never propagate to the caller`() {
        val throwing = object : ScreenAwakeMechanism {
            override fun engage() = error("boom")
            override fun disengage() = error("boom")
        }
        val c = ScreenAwakeController(throwing, Handler(Looper.getMainLooper()))
        c.acquire("run").close() // must not throw
    }
}
