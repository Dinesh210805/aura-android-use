package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolInfo
import com.aura.aura_ui.agent.mcpbridge.McpToolSource
import com.aura.aura_ui.agent.mcpbridge.ToolMeta
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMcpManagerTest {
    private val inProcess = object : McpToolSource {
        override suspend fun listTools(query: String?) = listOf(McpToolInfo("in-process", "tap", "d", ToolMeta()))
        override suspend fun call(server: String, tool: String, args: JsonObject) =
            CallToolResult(content = listOf(TextContent("ok")), isError = false)
    }

    @Test fun `no enabled servers yields null composite (today's behavior preserved)`() = runTest {
        val mgr = RemoteMcpManager.forTest(connections = emptyList())
        assertNull(mgr.connectedSources(emptyList(), inProcess))
        assertNull(mgr.connectedInstructions(emptyList()))
    }
}
