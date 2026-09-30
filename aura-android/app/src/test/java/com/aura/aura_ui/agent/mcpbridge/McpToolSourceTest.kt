package com.aura.aura_ui.agent.mcpbridge

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the [McpToolSource] contract against a fake — the real MCP SDK [Client] is
 * hard to construct in a unit test, so behaviour is pinned at the interface level.
 */
class McpToolSourceTest {
    private class FakeSource(private val tools: List<McpToolInfo>) : McpToolSource {
        var called: Triple<String, String, JsonObject>? = null
        override suspend fun listTools(query: String?) =
            if (query == null) tools
            else tools.filter { it.name.contains(query) || it.description.contains(query) }
        override suspend fun call(server: String, tool: String, args: JsonObject): CallToolResult {
            called = Triple(server, tool, args)
            return CallToolResult(content = listOf(TextContent("done")), isError = false)
        }
    }

    private val sample = listOf(
        McpToolInfo("in-process", "tap", "tap an element", ToolMeta()),
        McpToolInfo("in-process", "perceive_screen", "see the screen", ToolMeta(readOnly = true)),
    )

    @Test fun `listTools with null returns all`() = runTest {
        assertEquals(2, FakeSource(sample).listTools(null).size)
    }

    @Test fun `listTools filters by query`() = runTest {
        val r = FakeSource(sample).listTools("perceive")
        assertEquals(1, r.size); assertEquals("perceive_screen", r.first().name)
    }

    @Test fun `call forwards server tool and args`() = runTest {
        val src = FakeSource(sample)
        val r = src.call("in-process", "tap", buildJsonObject {})
        assertEquals(false, r.isError)
        assertEquals("tap", src.called!!.second)
        assertTrue((r.content.first() as TextContent).text == "done")
    }
}
