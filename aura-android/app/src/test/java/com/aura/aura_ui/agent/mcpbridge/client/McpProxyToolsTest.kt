package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolInfo
import com.aura.aura_ui.agent.mcpbridge.McpToolSource
import com.aura.aura_ui.agent.mcpbridge.ToolMeta
import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.PreToolDecision
import com.aura.aura_ui.agent.mcpbridge.hooks.PreToolHook
import com.aura.aura_ui.agent.mcpbridge.hooks.ToolHookChain
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProxyToolsTest {
    private class Fake(val tools: List<McpToolInfo>) : McpToolSource {
        var called: Triple<String, String, JsonObject>? = null
        override suspend fun listTools(query: String?) = tools
        override suspend fun call(server: String, tool: String, args: JsonObject): CallToolResult {
            called = Triple(server, tool, args)
            return CallToolResult(content = listOf(TextContent("ok")), isError = false)
        }
    }
    private val src = Fake(listOf(McpToolInfo("github", "create_issue", "make an issue", ToolMeta())))
    private val ctx = HookContext(confirm = { true })

    @Test fun `listMcpTools renders server and tool names`() = runTest {
        val proxy = McpProxyTools(src, ToolHookChain(emptyList(), emptyList()), ctx)
        val out = proxy.listMcpTools(null)
        assertTrue(out.contains("github")); assertTrue(out.contains("create_issue"))
    }

    @Test fun `useMcpTool routes through the hook chain - deny blocks dispatch`() = runTest {
        val deny = object : PreToolHook {
            override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext) =
                PreToolDecision.Deny("not allowed")
        }
        val proxy = McpProxyTools(src, ToolHookChain(listOf(deny), emptyList()), ctx)
        val r = proxy.useMcpTool("github", "create_issue", "{}")
        assertTrue(r.isError == true)
        assertTrue(src.called == null) // dispatch never happened
    }

    @Test fun `useMcpTool with malformed json returns model-readable error`() = runTest {
        val proxy = McpProxyTools(src, ToolHookChain(emptyList(), emptyList()), ctx)
        val r = proxy.useMcpTool("github", "create_issue", "{ not json")
        assertTrue(r.isError == true)
    }

    @Test fun `useMcpTool dispatches on proceed`() = runTest {
        val proxy = McpProxyTools(src, ToolHookChain(emptyList(), emptyList()), ctx)
        val r = proxy.useMcpTool("github", "create_issue", """{"title":"x"}""")
        assertTrue(r.isError == false)
        assertTrue(src.called?.second == "create_issue")
    }
}
