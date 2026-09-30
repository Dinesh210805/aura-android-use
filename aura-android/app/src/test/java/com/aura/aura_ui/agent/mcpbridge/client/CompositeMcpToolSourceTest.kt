package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolInfo
import com.aura.aura_ui.agent.mcpbridge.McpToolSource
import com.aura.aura_ui.agent.mcpbridge.ToolMeta
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeMcpToolSourceTest {
    private class Fake(val server: String, val names: List<String>) : McpToolSource {
        var called: Pair<String, String>? = null
        override suspend fun listTools(query: String?) =
            names.map { McpToolInfo(server, it, "d", ToolMeta()) }
        override suspend fun call(server: String, tool: String, args: JsonObject): CallToolResult {
            called = server to tool
            return CallToolResult(content = listOf(TextContent("$server/$tool")), isError = false)
        }
    }

    private val inproc = Fake("in-process", listOf("tap", "perceive_screen"))
    private val remote = Fake("github", listOf("create_issue", "tap")) // "tap" collides

    @Test fun `fan-out merges sources`() = runTest {
        val c = CompositeMcpToolSource(listOf(inproc, remote))
        val names = c.listTools(null).map { it.name }
        assertTrue(names.contains("create_issue"))
        assertTrue(names.contains("perceive_screen"))
    }

    @Test fun `remote tool cannot shadow an in-process tool`() = runTest {
        val c = CompositeMcpToolSource(listOf(inproc, remote))
        val taps = c.listTools(null).filter { it.name == "tap" }
        assertEquals(1, taps.size)
        assertEquals("in-process", taps.first().server)
    }

    @Test fun `call routes by server id`() = runTest {
        val c = CompositeMcpToolSource(listOf(inproc, remote))
        c.call("github", "create_issue", buildJsonObject {})
        assertEquals("github" to "create_issue", remote.called)
    }

    @Test fun `unknown server returns error result`() = runTest {
        val c = CompositeMcpToolSource(listOf(inproc, remote))
        val r = c.call("nope", "x", buildJsonObject {})
        assertTrue(r.isError == true)
    }
}
