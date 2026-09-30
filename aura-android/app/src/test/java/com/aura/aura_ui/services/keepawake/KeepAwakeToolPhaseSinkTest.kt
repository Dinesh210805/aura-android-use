package com.aura.aura_ui.services.keepawake

import android.os.Handler
import android.os.Looper
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.ToolPhaseSink
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class KeepAwakeToolPhaseSinkTest {

    private class FakeMechanism : ScreenAwakeMechanism {
        var engaged = false
        override fun engage() { engaged = true }
        override fun disengage() { engaged = false }
    }

    private val mechanism = FakeMechanism()
    private val controller = ScreenAwakeController(mechanism, Handler(Looper.getMainLooper()))
    private val sink = KeepAwakeToolPhaseSink(controller, Handler(Looper.getMainLooper()))

    private fun started(tool: String, scope: McpScope) =
        sink.onPhase(tool, scope, ToolPhaseSink.Phase.STARTED)
    private fun completed(tool: String, scope: McpScope) =
        sink.onPhase(tool, scope, ToolPhaseSink.Phase.COMPLETED)

    @Test fun `WRITE tool starting acquires the screen`() {
        started("tap", McpScope.WRITE)
        assertTrue(mechanism.engaged)
    }

    @Test fun `screen-reading READ tool acquires the screen`() {
        started("perceive_screen", McpScope.READ)
        assertTrue(mechanism.engaged)
    }

    @Test fun `pure-info tools never light the screen - merely connected stays asleep`() {
        started("echo", McpScope.READ)
        completed("echo", McpScope.READ)
        started("web_search", McpScope.READ)
        assertFalse(mechanism.engaged)
    }

    @Test fun `end_session completing releases immediately`() {
        started("tap", McpScope.WRITE)
        completed("tap", McpScope.WRITE)
        started("end_session", McpScope.READ)
        completed("end_session", McpScope.READ)
        assertFalse(mechanism.engaged)
    }

    @Test fun `silent client is released by the idle deadline`() {
        started("tap", McpScope.WRITE)
        completed("tap", McpScope.WRITE)
        shadowOf(Looper.getMainLooper())
            .idleFor(KeepAwakeToolPhaseSink.IDLE_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS)
        assertFalse(mechanism.engaged)
    }

    @Test fun `activity slides the idle deadline - think-gaps shorter than the timeout survive`() {
        val looper = shadowOf(Looper.getMainLooper())
        started("tap", McpScope.WRITE)
        looper.idleFor(2, TimeUnit.MINUTES) // client thinking…
        completed("tap", McpScope.WRITE)     // …then activity again
        looper.idleFor(2, TimeUnit.MINUTES)
        assertTrue(mechanism.engaged)        // 4 min total but never 3 min silent
        looper.idleFor(KeepAwakeToolPhaseSink.IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertFalse(mechanism.engaged)
    }

    @Test fun `one session lease - repeated tools do not stack holds`() {
        started("tap", McpScope.WRITE)
        completed("tap", McpScope.WRITE)
        started("type_text", McpScope.WRITE)
        completed("type_text", McpScope.WRITE)
        started("end_session", McpScope.READ)
        completed("end_session", McpScope.READ)
        assertFalse(mechanism.engaged) // a stacked lease would still hold here
    }

    @Test fun `a new run after release re-acquires cleanly`() {
        started("tap", McpScope.WRITE)
        started("end_session", McpScope.READ)
        completed("end_session", McpScope.READ)
        started("launch_app", McpScope.WRITE)
        assertTrue(mechanism.engaged)
    }

    @Test fun `explicit release is safe when nothing is held`() {
        sink.release()
        assertFalse(mechanism.engaged)
    }
}
