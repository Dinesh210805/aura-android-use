package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.TRUNCATION_MARKER
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1: untrusted remote tool results must be size-capped like the trusted in-process
 * path (30k) — an uncapped remote result is context blowout + maximal prompt-injection
 * real estate. `encodeRemoteResultText` is the pure rendering behind
 * `CallToolResultKoogTool.encodeResultToString`.
 */
class RemoteResultEncodingTest {

    @Test
    fun `joins text content for the model`() {
        val r = CallToolResult(content = listOf(TextContent("a"), TextContent("b")), isError = false)
        assertEquals("a\nb", encodeRemoteResultText(r))
    }

    @Test
    fun `errors keep the Error prefix`() {
        val r = CallToolResult(content = listOf(TextContent("boom")), isError = true)
        assertEquals("Error: boom", encodeRemoteResultText(r))
    }

    @Test
    fun `blank results keep their placeholders`() {
        assertEquals("(no content)", encodeRemoteResultText(CallToolResult(content = emptyList(), isError = false)))
        assertEquals(
            "Error: tool call blocked or failed",
            encodeRemoteResultText(CallToolResult(content = emptyList(), isError = true)),
        )
    }

    @Test
    fun `an oversized remote result is capped at the remote budget`() {
        val huge = "y".repeat(500_000)
        val r = CallToolResult(content = listOf(TextContent(huge)), isError = false)
        val out = encodeRemoteResultText(r)
        assertTrue(
            "remote results must be capped (got ${out.length} chars)",
            out.length <= REMOTE_RESULT_MAX_CHARS + TRUNCATION_MARKER.length,
        )
        assertTrue(out.contains(TRUNCATION_MARKER))
    }

    @Test
    fun `an oversized ERROR result is capped too`() {
        val huge = "e".repeat(500_000)
        val r = CallToolResult(content = listOf(TextContent(huge)), isError = true)
        val out = encodeRemoteResultText(r)
        assertTrue(out.startsWith("Error: "))
        assertTrue(out.length <= "Error: ".length + REMOTE_RESULT_MAX_CHARS + TRUNCATION_MARKER.length)
    }
}
