package com.aura.mcp.tools

import com.aura.mcp.server.AuraInstructions
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

/**
 * Spec 2026-07-17 context delivery — `get_usage_guide`.
 *
 * The handshake carries only the ~1.9 KB survival kit (clients truncate the
 * MCP `instructions` field); this tool serves the FULL behavioral contract on
 * demand, whole or one topic at a time. A tool (not just the `aura://guide`
 * resource) because tools are the one primitive every MCP client reliably
 * exposes to the model.
 */
internal object UsageGuide {

    /** Pure lookup so the routing is unit-testable without a server. */
    fun guideFor(topic: String?): String {
        val t = topic?.trim()?.lowercase()
        return when {
            t.isNullOrBlank() || t == "full" -> AuraInstructions.text
            else -> AuraInstructions.sections[t]
                ?: ("Unknown topic \"$t\". Available topics: " +
                    (AuraInstructions.sections.keys + "full").joinToString(", ") +
                    ". Call again with one of these, or omit `topic` for the full guide.")
        }
    }
}

internal fun Server.registerGetUsageGuideTool() {
    scopedTool(
        name = "get_usage_guide",
        description = """
            The full playbook for driving this device well — the handshake
            instructions are only a summary. Call this once before your first
            multi-step task, or fetch a single topic when you need its doctrine:
            decision_tree (which tool for which goal), perception (som_ids,
            dual-track screenshots), loop (act → observe → decide), loading,
            trust (labels vs pixels), deeplinks (the express lane), action_plane
            (intents/media/notifications), safety (hard blocks), stop (when to
            ask the user), efficiency (cheapest sufficient tool), browser
            (driving web pages — read the DOM, never perceive_screen a web
            page). Omit `topic` or pass "full" for everything.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "topic" to stringSchema(
                        "Optional. One of: decision_tree, perception, loop, loading, trust, " +
                            "deeplinks, action_plane, safety, stop, efficiency, browser, full. " +
                            "Default: full.",
                    ),
                ),
            ),
        ),
    ) { request ->
        CallToolResult(
            content = listOf(TextContent(UsageGuide.guideFor(request.arguments?.stringArg("topic")))),
        )
    }
}
