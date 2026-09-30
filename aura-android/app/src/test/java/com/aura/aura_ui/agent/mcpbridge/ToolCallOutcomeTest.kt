package com.aura.aura_ui.agent.mcpbridge

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O1: MCP failures never throw — they return `CallToolResult(isError=true)` — so the
 * agent's `onToolCallCompleted` must derive success from the result, not hardcode it.
 * O3: the logged summary must never embed base64 image payloads.
 */
class ToolCallOutcomeTest {

    @Test
    fun `an isError result is a failure`() {
        val r = CallToolResult(content = listOf(TextContent("Blocked: perceive the screen first")), isError = true)
        val outcome = ToolCallOutcome.from(r)
        assertFalse("isError=true must be reported as a failed call", outcome.success)
        assertTrue(outcome.summary.contains("perceive the screen first"))
    }

    @Test
    fun `a normal result is a success with its text as summary`() {
        val r = CallToolResult(content = listOf(TextContent("tapped (540, 1200)")), isError = false)
        val outcome = ToolCallOutcome.from(r)
        assertTrue(outcome.success)
        assertEquals("tapped (540, 1200)", outcome.summary)
    }

    @Test
    fun `image content is elided from the summary, never embedded`() {
        val bigBase64 = "A".repeat(500_000)
        val r = CallToolResult(
            content = listOf(TextContent("elements: 12"), ImageContent(data = bigBase64, mimeType = "image/png")),
            isError = false,
        )
        val outcome = ToolCallOutcome.from(r)
        assertTrue(outcome.summary.contains("elements: 12"))
        assertTrue("summary must not carry the base64 payload", outcome.summary.length < 1_000)
    }

    @Test
    fun `a null result is a success with an empty summary`() {
        val outcome = ToolCallOutcome.from(null)
        assertTrue(outcome.success)
        assertEquals("", outcome.summary)
    }

    @Test
    fun `a non-CallToolResult falls back to toString`() {
        val outcome = ToolCallOutcome.from("plain koog result")
        assertTrue(outcome.success)
        assertEquals("plain koog result", outcome.summary)
    }

    // ── JSON-encoded lane ────────────────────────────────────────────────────
    // On-device, Koog's onToolCallCompleted carries the ENCODED form of the MCP
    // result (a JSON rendering of CallToolResult), not the CallToolResult
    // instance — the `as?` cast fails and every denied gesture was logged as a
    // success (observed live: tap denials with success=True dur=2ms). The JSON
    // shape must go through the same isError/text extraction as the typed shape.

    @Test
    fun `a JSON-encoded isError result is a failure with the inner text as summary`() {
        val encoded =
            """{"content":[{"text":"{\"success\":false,\"error\":\"no_exact_text_match\"}", "type":"text"}], "isError":true}"""
        val outcome = ToolCallOutcome.from(encoded)
        assertFalse("JSON-encoded isError=true must be reported as a failed call", outcome.success)
        assertTrue(outcome.summary.contains("no_exact_text_match"))
        assertFalse("summary must be the inner text, not the envelope", outcome.summary.contains("\"content\""))
    }

    @Test
    fun `a JSON-encoded success result stays a success`() {
        val encoded = """{"content":[{"text":"{\"success\":true,\"action\":\"press_enter\"}","type":"text"}],"isError":false}"""
        val outcome = ToolCallOutcome.from(encoded)
        assertTrue(outcome.success)
        assertTrue(outcome.summary.contains("press_enter"))
    }

    @Test
    fun `a JSON-encoded result without isError defaults to success`() {
        val encoded = """{"content":[{"text":"ok","type":"text"}]}"""
        val outcome = ToolCallOutcome.from(encoded)
        assertTrue(outcome.success)
        assertEquals("ok", outcome.summary)
    }

    @Test
    fun `JSON-encoded image content is elided from the summary`() {
        val bigBase64 = "A".repeat(500_000)
        val encoded =
            """{"content":[{"text":"elements: 12","type":"text"},{"data":"$bigBase64","mimeType":"image/png","type":"image"}],"isError":false}"""
        val outcome = ToolCallOutcome.from(encoded)
        assertTrue(outcome.summary.contains("elements: 12"))
        assertTrue("summary must not carry the base64 payload", outcome.summary.length < 1_000)
    }

    @Test
    fun `a JSON string that is not a result envelope falls back to toString`() {
        val outcome = ToolCallOutcome.from("""{"foo":"bar"}""")
        assertTrue(outcome.success)
        assertEquals("""{"foo":"bar"}""", outcome.summary)
    }
}
