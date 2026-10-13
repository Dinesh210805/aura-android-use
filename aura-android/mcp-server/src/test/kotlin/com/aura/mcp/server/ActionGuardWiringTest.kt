package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** [ActionGuard] at the `scopedTool` chokepoint: every agent, not only the on-device one, hits it. */
class ActionGuardWiringTest {

    private val ran = mutableListOf<String>()

    private fun server(guard: ActionGuard = ActionGuard()): Server {
        val server = Server(
            Implementation(name = "guard-wiring-test", version = "0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        server.installSinks(ServerSinks(guard = guard))
        listOf("read_screen", "tap", "end_session").forEach { tool ->
            server.scopedTool(tool, "test $tool") { _ ->
                ran += tool
                CallToolResult(content = listOf(TextContent("""{"success":true}""")))
            }
        }
        return server
    }

    private suspend fun Server.call(tool: String, args: JsonObject = JsonObject(emptyMap())): CallToolResult =
        tools.getValue(tool).handler(CallToolRequest(CallToolRequestParams(name = tool, arguments = args)))

    private fun CallToolResult.payload(): JsonObject =
        Json.parseToJsonElement((content.single() as TextContent).text.orEmpty()).jsonObject

    @Test
    fun `a blind tap is refused before its handler runs, with a branchable error code`() = runBlocking<Unit> {
        val result = server().call("tap")

        assertEquals(true, result.isError)
        assertEquals("action_guard", result.payload()["error"]?.jsonPrimitive?.content)
        assertEquals("perceive_first", result.payload()["reason"]?.jsonPrimitive?.content)
        assertEquals(emptyList(), ran, "a refused call must not reach its handler")
    }

    @Test
    fun `a tap after reading the screen runs`() = runBlocking<Unit> {
        val server = server()
        server.call("read_screen")
        val result = server.call("tap")

        assertNotEquals(true, result.isError)
        assertEquals(listOf("read_screen", "tap"), ran)
    }

    @Test
    fun `a successful end_session resets the guard for the next task`() = runBlocking<Unit> {
        val server = server()
        server.call("read_screen")
        server.call("end_session")

        // Without the reset, the read above would still ground this tap.
        assertEquals("perceive_first", server.call("tap").payload()["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a bare server without a guard refuses nothing`() = runBlocking<Unit> {
        val server = Server(
            Implementation(name = "no-guard", version = "0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        server.scopedTool("tap", "test tap") { _ -> CallToolResult(content = listOf(TextContent("ok"))) }

        assertNotEquals(true, server.call("tap").isError)
    }
}
