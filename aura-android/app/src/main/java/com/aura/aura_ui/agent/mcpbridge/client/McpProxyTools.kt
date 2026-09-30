package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolSource
import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.ToolHookChain
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Backs the deferred `list_mcp_tools` / `use_mcp_tool` Koog tools (parent spec §3.5 / V.4).
 * Every invocation is routed through the same [ToolHookChain] as in-process tools — a remote
 * call is NOT exempt from ActionGuard/confirm/loop gating, and a malformed arg blob yields a
 * model-readable error tool-result rather than throwing into the agent loop.
 */
class McpProxyTools(
    private val source: McpToolSource,
    private val chain: ToolHookChain,
    private val ctx: HookContext,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listMcpTools(query: String?): String {
        val tools = McpPayloadGuard.capTools(source.listTools(query))
        if (tools.isEmpty()) return "No MCP tools available."
        return tools.joinToString("\n") { "• ${it.server}/${it.name} — ${it.description}" }
    }

    suspend fun useMcpTool(server: String, tool: String, argsJson: String): CallToolResult {
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            ?: return errorResult("args_json is not a valid JSON object")
        return chain.runGatedToolCall(tool, args, ctx) { effective ->
            source.call(server, tool, effective)
        }
    }

    private fun errorResult(text: String) =
        CallToolResult(content = listOf(TextContent(text)), isError = true)
}
