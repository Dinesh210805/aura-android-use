package com.aura.aura_ui.agent.mcpbridge.telemetry

/**
 * Where a completed MCP tool call gets reported. Only the tool name, timing, and
 * success/failure ever cross this boundary — never arguments, results, or screen
 * content. See docs/superpowers/specs/2026-07-23-remote-control-analytics-design.md.
 */
interface ToolTelemetrySink {
    fun reportToolCall(toolName: String, durationMs: Long, success: Boolean)
}
