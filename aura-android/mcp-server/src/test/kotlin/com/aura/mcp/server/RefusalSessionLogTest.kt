package com.aura.mcp.server

import com.aura.mcp.bridge.SessionLogSink
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Gate refusals reach the session log through `onToolRefused`, never through the screenshotting `onToolStart`. */
class RefusalSessionLogTest {

    private class RecordingSessionSink : SessionLogSink {
        val started = CopyOnWriteArrayList<String>()
        val refused = CopyOnWriteArrayList<Pair<String, String>>()
        override fun onToolStart(toolName: String, argsJson: String?, tokenId: String?, agentLabel: String?) {
            started += toolName
        }
        override fun onToolEnd(toolName: String, success: Boolean, outputSummary: String, durationMs: Long) = Unit
        override fun onToolRefused(toolName: String, reason: String, tokenId: String?, agentLabel: String?) {
            refused += toolName to reason
        }
        override fun endSession(reason: String) = Unit
    }

    @Test
    fun `a call refused by the control lock is logged as refused without starting a capture`() = runBlocking {
        val sink = RecordingSessionSink()
        val server = Server(
            serverInfo = Implementation(name = "refusal-log-test", version = "0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        server.installSinks(ServerSinks(session = sink, lock = ControlLockGate { true }))
        var handlerRan = false
        server.scopedTool(name = "tap", description = "test tap") {
            handlerRan = true
            CallToolResult(content = listOf(TextContent("tapped")))
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clientEnd = InMemoryMcpTransport(EmptyCoroutineContext, scope)
            val serverEnd = InMemoryMcpTransport(EmptyCoroutineContext, scope)
            clientEnd.peer = serverEnd
            serverEnd.peer = clientEnd
            server.createSession(serverEnd)
            val client = Client(clientInfo = Implementation(name = "test-client", version = "0"))

            val result = withTimeout(10_000) {
                client.connect(clientEnd)
                client.callTool(name = "tap", arguments = JsonObject(emptyMap()))
            }

            assertEquals(true, result?.isError)
            assertTrue(!handlerRan, "a refused call must not reach the handler")
            assertEquals(listOf("tap" to "paused_by_user"), sink.refused.toList())
            assertTrue(sink.started.isEmpty(), "onToolStart screenshots — refusals must not call it")
        } finally {
            scope.cancel()
        }
    }
}
