package com.aura.aura_ui.services

import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.ToolPhaseSink
import java.util.concurrent.atomic.AtomicInteger

/**
 * Maps live MCP tool dispatches into [AuraStatus] transitions on the
 * status-bar chip.
 *
 * Mapping rules:
 *  - WRITE-scope tool started → [AuraStatus.Acting]
 *  - `verify_action` or `wait_for` started → [AuraStatus.Verifying]
 *  - Any other READ tool started → [AuraStatus.Thinking]
 *    (perceive_screen, read_screen, get_screenshot, web_search, ...)
 *  - Last in-flight call finishes, however it finished → [AuraStatus.Idle]
 *
 * **Tool errors are not a status.** They used to set a deliberately sticky
 * `Stuck` state, but errors are ordinary in an agent loop — a stale `som_id`,
 * a missed tap, a retry — so one recoverable hiccup wedged the chip for the
 * rest of the process, and (because [ChipVisibilityPolicy] renders while
 * status != Idle) pinned a pill in the notch with it. An ERRORED call is just
 * a call that finished. Only the *run* layer can know about terminal failure.
 *
 * Concurrency: MCP supports concurrent tool calls on the same session, so
 * in-flight calls are counted with an [AtomicInteger] and the status only
 * drops back to Idle when the count reaches zero.
 */
class StatusToolPhaseSink(
    private val statusController: StatusController,
) : ToolPhaseSink {

    private val inFlight = AtomicInteger(0)

    override fun onPhase(toolName: String, scope: McpScope, phase: ToolPhaseSink.Phase) {
        when (phase) {
            ToolPhaseSink.Phase.STARTED -> {
                inFlight.incrementAndGet()
                statusController.set(statusFor(toolName, scope))
            }
            // A call that errored is still a call that finished. Same bookkeeping
            // as COMPLETED — the difference is reported to the caller in the tool
            // result and to the audit log, not by wedging the user's status chip.
            ToolPhaseSink.Phase.COMPLETED, ToolPhaseSink.Phase.ERRORED -> settle()

            // Deliberately does NOT touch the counter. ScopedToolRegistration fires
            // STARTED only *after* auth/scope/policy pass, so a denied call never
            // incremented. Decrementing here would drive the count negative and no
            // later call could ever bring it back to zero — wedging the chip a
            // second way. There is also nothing to show: nothing ran.
            ToolPhaseSink.Phase.SCOPE_DENIED -> Unit
        }
    }

    /** One in-flight call finished; go Idle once none are left. */
    private fun settle() {
        if (inFlight.decrementAndGet() <= 0) {
            // Clamp rather than assume: a stray COMPLETED with no matching STARTED
            // would otherwise leave the count negative and permanently "busy".
            inFlight.set(0)
            statusController.set(AuraStatus.Idle)
        }
    }

    private fun statusFor(toolName: String, scope: McpScope): AuraStatus = when {
        toolName == "verify_action" || toolName == "wait_for" -> AuraStatus.Verifying
        scope == McpScope.WRITE -> AuraStatus.Acting
        else -> AuraStatus.Thinking
    }
}
