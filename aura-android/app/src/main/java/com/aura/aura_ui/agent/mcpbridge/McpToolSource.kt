package com.aura.aura_ui.agent.mcpbridge

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/** One tool discoverable across the connected MCP servers. */
data class McpToolInfo(
    val server: String,
    val name: String,
    val description: String,
    val meta: ToolMeta,
)

/**
 * Discovery + invocation across MCP servers — the deferral seam that keeps the model's
 * tool list small while still reaching an unbounded MCP surface.
 *
 * v1 has one server (the in-process one). Sub-project #2 adds remote third-party servers
 * behind this same interface, plus the `list_mcp_tools`/`use_mcp_tool` Koog tools that
 * expose it to the model (deferred until there is a second server to reach — wrapping a
 * single in-process server would just duplicate the directly-registered tools).
 *
 * Adversarial note: results from any server are untrusted input — callers must still
 * size-cap and schema-validate, and a server-declared [ToolMeta] is advisory, never
 * an authority grant.
 */
interface McpToolSource {
    /** All tools, or those whose name/description match [query] (case-insensitive). */
    suspend fun listTools(query: String?): List<McpToolInfo>

    /** Invoke [tool] on [server] with [args]. */
    suspend fun call(server: String, tool: String, args: JsonObject): CallToolResult
}

/** Backs [McpToolSource] with the already-connected in-process [Client]. */
class InProcessMcpToolSource(
    private val client: Client,
    private val serverId: String = "in-process",
) : McpToolSource {
    override suspend fun listTools(query: String?): List<McpToolInfo> {
        val all = client.listTools()?.tools.orEmpty().map { t ->
            McpToolInfo(serverId, t.name, t.description.orEmpty(), ToolMetaTable.metaFor(t.name))
        }
        return if (query.isNullOrBlank()) {
            all
        } else {
            all.filter { it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }
        }
    }

    override suspend fun call(server: String, tool: String, args: JsonObject): CallToolResult =
        client.callTool(name = tool, arguments = args)
}
