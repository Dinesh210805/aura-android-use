package com.aura.aura_ui.agent.mcpbridge.telemetry

import com.aura.aura_ui.agent.mcpbridge.dispatchWithTelemetry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSink : ToolTelemetrySink {
    var lastToolName: String? = null
    var lastDurationMs: Long? = null
    var lastSuccess: Boolean? = null
    var callCount = 0

    override fun reportToolCall(toolName: String, durationMs: Long, success: Boolean) {
        callCount++
        lastToolName = toolName
        lastDurationMs = durationMs
        lastSuccess = success
    }
}

class DispatchWithTelemetryTest {
    private fun ok() = CallToolResult(content = listOf(TextContent("ok")), isError = false)
    private fun failed() = CallToolResult(content = listOf(TextContent("bad")), isError = true)

    @Test fun `successful dispatch reports success true`() = runTest {
        val sink = FakeSink()
        val result = dispatchWithTelemetry("tap", sink) { ok() }
        assertEquals(false, result.isError)
        assertEquals(1, sink.callCount)
        assertEquals("tap", sink.lastToolName)
        assertEquals(true, sink.lastSuccess)
        assertTrue("duration must be non-negative", (sink.lastDurationMs ?: -1L) >= 0L)
    }

    @Test fun `error result reports success false`() = runTest {
        val sink = FakeSink()
        val result = dispatchWithTelemetry("perceive_screen", sink) { failed() }
        assertEquals(true, result.isError)
        assertEquals(false, sink.lastSuccess)
        assertEquals("perceive_screen", sink.lastToolName)
    }

    @Test fun `reports exactly once per call`() = runTest {
        val sink = FakeSink()
        dispatchWithTelemetry("tap", sink) { ok() }
        dispatchWithTelemetry("tap", sink) { ok() }
        assertEquals(2, sink.callCount)
    }
}
