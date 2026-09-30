package com.aura.aura_ui.services

import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.ToolPhaseSink

/**
 * Fans one [ToolPhaseSink] port out to several consumers (status chip,
 * keep-awake, …). [McpServerController] accepts a single sink; this keeps
 * that contract while letting the app attach independent observers.
 *
 * A failing sink is isolated — one observer throwing must not starve the
 * others or the MCP dispatch thread.
 */
class CompositeToolPhaseSink(
    private val sinks: List<ToolPhaseSink>,
) : ToolPhaseSink {

    override fun onPhase(toolName: String, scope: McpScope, phase: ToolPhaseSink.Phase) {
        sinks.forEach { sink ->
            runCatching { sink.onPhase(toolName, scope, phase) }
        }
    }
}
