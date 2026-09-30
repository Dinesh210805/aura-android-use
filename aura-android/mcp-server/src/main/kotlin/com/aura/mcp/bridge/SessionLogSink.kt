package com.aura.mcp.bridge

/**
 * Phase 10B — forensic-detail sink for every MCP tool dispatch.
 *
 * Distinct from [McpAuditLogger] (which records a one-line summary per
 * call in a ring buffer for the in-app Activity Log) and from
 * [ToolPhaseSink] (which drives the live AuraStatus chip). This sink
 * collects the full forensic record:
 *
 *   • a raw screenshot taken at the moment the tool fired (NOT the
 *     SoM-annotated one — annotation belongs to the agent's reasoning;
 *     the raw image is the audit ground truth)
 *   • the tool name + args + textual output
 *   • the calling agent's identity (from the bearer-token label set at
 *     pairing time — e.g. "Laptop / VS Code")
 *   • an explicit session boundary, so multiple tool calls in close
 *     temporal proximity get grouped into one navigable entry
 *
 * Implementation lives in `:app` because screenshot capture and on-disk
 * storage are platform concerns. The interface stays here so the tool
 * dispatcher can fire events without depending on Android.
 *
 * Implementations must be cheap and non-blocking — the methods are
 * called on the MCP request thread. Heavy work (screenshot capture,
 * disk write) should be dispatched to a background coroutine inside
 * the implementation.
 */
interface SessionLogSink {

    /**
     * Called just before a tool's handler runs. Auth and scope have
     * already passed at this point — denied calls do not fire this.
     */
    fun onToolStart(
        toolName: String,
        argsJson: String?,
        tokenId: String?,
        agentLabel: String?,
    )

    /**
     * Called after the handler returns (or throws). `outputSummary` is
     * a short textual preview of the response, truncated by the caller
     * to keep log entries bounded.
     */
    fun onToolEnd(
        toolName: String,
        success: Boolean,
        outputSummary: String,
        durationMs: Long,
    )

    /**
     * Called by the `end_session` tool. Closes the current session log
     * entry. Future tool calls open a new session. The MCP server
     * itself keeps listening — this is a soft boundary, not a hard
     * shutdown.
     */
    fun endSession(reason: String)
}

/** No-op default — used in tests or when the host doesn't wire a sink. */
object NoOpSessionLogSink : SessionLogSink {
    override fun onToolStart(toolName: String, argsJson: String?, tokenId: String?, agentLabel: String?) = Unit
    override fun onToolEnd(toolName: String, success: Boolean, outputSummary: String, durationMs: Long) = Unit
    override fun endSession(reason: String) = Unit
}
