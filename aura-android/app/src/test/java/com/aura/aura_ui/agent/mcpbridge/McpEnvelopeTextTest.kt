package com.aura.aura_ui.agent.mcpbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strings in this file are copied verbatim out of a session exported from a real device
 * (2026-08-17). The point of using real captures is that the shape is not valid JSON, and a
 * hand-written "realistic" example would almost certainly have been written valid — which is
 * exactly how this went unnoticed while `ToolCallOutcomeTest` stayed green.
 */
class McpEnvelopeTextTest {

    @Test
    fun `an isError envelope is recognised despite unescaped inner json`() {
        val raw = """{"content":[{"text":"{"success":false,"action":"press_enter"}", "type":"text"}], "isError":true}"""

        val env = McpEnvelopeText.parse(raw)!!

        assertEquals(true, env.isError)
        assertEquals(1, env.parts.size)
        assertEquals("""{"success":false,"action":"press_enter"}""", env.parts[0].text)
    }

    @Test
    fun `a success envelope reports isError false`() {
        val raw = """{"content":[{"text":"Plan set (2 steps).", "type":"text"}], "isError":false}"""

        val env = McpEnvelopeText.parse(raw)!!

        assertEquals(false, env.isError)
        assertEquals("Plan set (2 steps).", env.parts[0].text)
    }

    @Test
    fun `a base64 image part is measured, never inlined`() {
        val base64 = "iVBORw0KGgo" + "A".repeat(4_000)
        val raw = """{"content":[{"text":"167 tree boxes, 0 vision boxes.", "type":"text"}, {"data":"$base64", "type":"image"}], "isError":false}"""

        val env = McpEnvelopeText.parse(raw)!!

        assertEquals(2, env.parts.size)
        assertFalse(env.parts[0].isBinary)
        assertTrue(env.parts[1].isBinary)
        assertEquals("", env.parts[1].text)
        assertTrue("must record a plausible byte size", env.parts[1].approxBytes > 2_000)
    }

    @Test
    fun `two text parts both survive`() {
        val raw = """{"content":[{"text":"{"success":true,"action":"tap"}", "type":"text"}, {"text":"{"post_action_observation":{"settled":true}}", "type":"text"}], "isError":false}"""

        val env = McpEnvelopeText.parse(raw)!!

        assertEquals(2, env.parts.size)
        assertTrue(env.parts[1].text.contains("post_action_observation"))
    }

    @Test
    fun `a payload truncated mid-value keeps what was captured`() {
        val raw = """{"content":[{"text":"167 tree boxes, 0 vision bo"""

        val env = McpEnvelopeText.parse(raw)!!

        assertEquals("167 tree boxes, 0 vision bo", env.parts[0].text)
        assertNull("no isError was recorded, so it must not be guessed", env.isError)
    }

    @Test
    fun `plain text is not an envelope`() {
        assertNull(McpEnvelopeText.parse("Step 1 marked done. Progress: 1/2 done."))
        assertNull(McpEnvelopeText.parse("""{"success":true,"action":"tap"}"""))
    }
}
