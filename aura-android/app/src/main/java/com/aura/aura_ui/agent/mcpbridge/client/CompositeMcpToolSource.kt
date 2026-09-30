package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolInfo
import com.aura.aura_ui.agent.mcpbridge.McpToolSource
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject

/**
 * Fans tool discovery + invocation across the in-process source and any connected remotes
 * (parent spec §3.4). Adversarial guarantees: tool lists are capped, and a remote tool may
 * never shadow a tool offered by [primaryServerId] (collisions resolve to the primary).
 */
class CompositeMcpToolSource(
    private val sources: List<McpToolSource>,
    private val primaryServerId: String = "in-process",
) : McpToolSource {

    override suspend fun listTools(query: String?): List<McpToolInfo> {
        val merged = sources.flatMap { McpPayloadGuard.capTools(it.listTools(query)) }
        val primaryNames = merged.filter { it.server == primaryServerId }.map { it.name }.toSet()
        return merged.filter { it.server == primaryServerId || it.name !in primaryNames }
    }

    override suspend fun call(server: String, tool: String, args: JsonObject): CallToolResult {
        // Route to whichever source owns [server]; probe by a marker is unavailable, so we
        // try each and let the owning one handle it. Sources are keyed by their tools' server id.
        val owner = sources.firstOrNull { src -> src.listTools(null).any { it.server == server } }
            ?: return CallToolResult(
                content = listOf(TextContent("Unknown MCP server '$server'")),
                isError = true,
            )
        return owner.call(server, tool, args)
    }
}
