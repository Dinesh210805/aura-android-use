package com.aura.mcp.bridge

/**
 * Phase 9 — observer port for live tool-dispatch transitions.
 *
 * Distinct from [McpAuditLogger]: the audit logger only fires *after* a
 * tool returns (one record per call). This sink fires *before* dispatch
 * too, so the host (`:app`) can drive a live status-bar chip that reflects
 * what is happening right now rather than what just happened.
 *
 * Implementations must be cheap and non-blocking — `onPhase` is called on
 * the MCP request thread. Drop work or post to a background dispatcher
 * inside the implementation if needed.
 *
 * The interface stays here in `:mcp-server` so the dispatcher can call it
 * without depending on anything Android.
 */
fun interface ToolPhaseSink {
    fun onPhase(toolName: String, scope: McpScope, phase: Phase)

    enum class Phase { STARTED, COMPLETED, ERRORED, SCOPE_DENIED }
}

/** No-op default — used in tests or when the host doesn't wire a sink. */
object NoOpToolPhaseSink : ToolPhaseSink {
    override fun onPhase(toolName: String, scope: McpScope, phase: ToolPhaseSink.Phase) = Unit
}
