package com.aura.aura_ui.services

import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.ToolPhaseSink
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status chip must always find its way back to Idle.
 *
 * A status that can get permanently wedged is worse than no status at all: it
 * lies about what the agent is doing, and because [ChipVisibilityPolicy] keeps
 * the chip rendered while status != Idle, a wedged status also pins a pill in
 * the notch forever — the exact complaint that policy exists to prevent.
 *
 * The historical bug: a tool ERROR set a deliberately *sticky* `Stuck` state.
 * But tool errors are ordinary in an agent loop — a stale `som_id`, a missed
 * tap, a retry — so one recoverable hiccup wedged the pill for the rest of the
 * process. Tool-level errors are not a status; the agent retries. Only the
 * *run* layer knows about terminal failure.
 */
class StatusToolPhaseSinkTest {

    private val controller = StatusController()
    private val sink = StatusToolPhaseSink(controller)

    private fun started(tool: String, scope: McpScope = McpScope.READ) =
        sink.onPhase(tool, scope, ToolPhaseSink.Phase.STARTED)

    private fun completed(tool: String, scope: McpScope = McpScope.READ) =
        sink.onPhase(tool, scope, ToolPhaseSink.Phase.COMPLETED)

    private fun errored(tool: String, scope: McpScope = McpScope.READ) =
        sink.onPhase(tool, scope, ToolPhaseSink.Phase.ERRORED)

    private fun scopeDenied(tool: String, scope: McpScope = McpScope.WRITE) =
        sink.onPhase(tool, scope, ToolPhaseSink.Phase.SCOPE_DENIED)

    // ── mapping ────────────────────────────────────────────────────────

    @Test
    fun `a WRITE tool shows Acting`() {
        started("tap", McpScope.WRITE)

        assertEquals(AuraStatus.Acting, controller.state.value)
    }

    @Test
    fun `a READ tool shows Thinking`() {
        started("perceive_screen", McpScope.READ)

        assertEquals(AuraStatus.Thinking, controller.state.value)
    }

    @Test
    fun `flow-control tools show Verifying`() {
        started("verify_action")
        assertEquals(AuraStatus.Verifying, controller.state.value)

        completed("verify_action")
        started("wait_for")
        assertEquals(AuraStatus.Verifying, controller.state.value)
    }

    // ── always returns to Idle ─────────────────────────────────────────

    @Test
    fun `a completed call returns to Idle`() {
        started("tap", McpScope.WRITE)
        completed("tap", McpScope.WRITE)

        assertEquals(AuraStatus.Idle, controller.state.value)
    }

    @Test
    fun `a tool error does NOT wedge the status`() {
        // The regression. Errors are normal and recoverable; the agent retries.
        started("tap", McpScope.WRITE)
        errored("tap", McpScope.WRITE)

        assertEquals(AuraStatus.Idle, controller.state.value)
    }

    @Test
    fun `a scope denial does NOT wedge the status`() {
        scopeDenied("launch_app")

        assertEquals(AuraStatus.Idle, controller.state.value)
    }

    @Test
    fun `an error mid-run still settles at Idle once everything drains`() {
        started("perceive_screen")
        started("tap", McpScope.WRITE)
        errored("tap", McpScope.WRITE)
        completed("perceive_screen")

        assertEquals(AuraStatus.Idle, controller.state.value)
    }

    // ── concurrency ────────────────────────────────────────────────────

    @Test
    fun `status stays busy while other calls are still in flight`() {
        started("perceive_screen")
        started("tap", McpScope.WRITE)

        completed("tap", McpScope.WRITE)

        // One call is still running — dropping to Idle here would under-report.
        assertEquals(AuraStatus.Acting, controller.state.value)
    }

    @Test
    fun `scope denial does not corrupt the in-flight count`() {
        // SCOPE_DENIED is emitted WITHOUT a preceding STARTED (ScopedToolRegistration
        // fires STARTED only after auth/scope/policy pass). Decrementing on denial
        // would drive the counter negative, so a later real call could never bring
        // it back to zero — wedging the pill a second way.
        scopeDenied("launch_app")
        scopeDenied("open_deeplink")

        started("perceive_screen")
        completed("perceive_screen")

        assertEquals(AuraStatus.Idle, controller.state.value)
    }

    @Test
    fun `a stray completion cannot drive the counter negative`() {
        completed("tap", McpScope.WRITE)

        started("perceive_screen")
        completed("perceive_screen")

        assertEquals(AuraStatus.Idle, controller.state.value)
    }
}
