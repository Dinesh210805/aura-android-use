package com.aura.mcp.server

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.tools.resolveForGesture
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real `scopedTool` pipeline and the real som_id resolver, reporting into a recording sink:
 * what the run log receives is what these steps are, in this order.
 */
class ToolTrailTest {

    @Test
    fun `a call reports its gates, the box it targeted, and its dispatch, in order`() = runBlocking {
        val steps = CopyOnWriteArrayList<Triple<String, String, String?>>()
        val server = Server(
            Implementation(name = "trail-test", version = "0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
        )
        server.installSinks(
            ServerSinks(trail = ToolTrailSink { tool, stage, name, _, detail, _ ->
                if (tool == "tap") steps += Triple(stage, name, detail)
            }),
        )
        val cache = PerceptionCache().apply {
            update(listOf(DetectedElement(somId = 7, bbox = BBox(10, 20, 110, 80), elementType = "button", label = "Send", confidence = 0.9f)))
        }
        server.scopedTool("tap", "test tap") { _ ->
            cache.resolveForGesture(7)
            CallToolResult(content = listOf(TextContent("ok")))
        }

        server.tools.getValue("tap").handler(
            CallToolRequest(CallToolRequestParams(name = "tap", arguments = buildJsonObject { put("som_id", 7) })),
        )

        assertEquals(
            listOf(
                "gate" to "Scope", "gate" to "SensitivePolicy", "gate" to "ControlLock", "gate" to "ForegroundGate",
                "target" to "7", "dispatch" to "tap", "server-post" to "ScreenGeneration",
            ),
            steps.map { it.first to it.second },
        )
        assertEquals("10,20,110,80", steps.single { it.first == "target" }.third)
    }
}
