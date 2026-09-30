package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolInfo
import com.aura.aura_ui.agent.mcpbridge.McpToolSource
import com.aura.aura_ui.agent.mcpbridge.ToolMeta
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/**
 * [McpToolSource] over one connected remote [Client]. Every tool is tagged with [serverId]
 * and gets a conservative [ToolMeta] (a third-party server's self-declared flags are not
 * trusted here). Untrusted listing bounds are applied by [CompositeMcpToolSource].
 */
class RemoteMcpToolSource(
    private val client: Client,
    private val serverId: String,
) : McpToolSource {
    override suspend fun listTools(query: String?): List<McpToolInfo> {
        val all = client.listTools()?.tools.orEmpty().map { t ->
            McpToolInfo(serverId, t.name, t.description.orEmpty(), ToolMeta())
        }
        return if (query.isNullOrBlank()) all
        else all.filter { it.name.contains(query, true) || it.description.contains(query, true) }
    }

    override suspend fun call(server: String, tool: String, args: JsonObject): CallToolResult =
        client.callTool(name = tool, arguments = args)
}
