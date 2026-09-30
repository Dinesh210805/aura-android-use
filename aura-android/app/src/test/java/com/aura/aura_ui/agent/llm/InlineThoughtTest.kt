package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pulled session showed `LlmCall.reasoning` null on all six calls while the model's actual
 * thinking sat inside `LlmCall.response` as `<thought>…</thought>`. Gemini on the OpenAI-compat
 * endpoint inlines the thought in `content` instead of returning `reasoning_content`, so
 * [AgentLlmTap.extractReasoning] found nothing. Anyone reading `reasoning` to ask "why did it do
 * that" concludes the run captured no reasoning at all — the opposite of the truth.
 */
class InlineThoughtTest {

    @Test
    fun `an inlined thought becomes reasoning and leaves the body`() {
        val body = "<thought>First I should look at the screen.</thought>\n→ tool_call tap({\"som_id\":3})"
        val split = splitInlineThought(body)

        assertEquals("First I should look at the screen.", split.reasoning)
        assertEquals("→ tool_call tap({\"som_id\":3})", split.body)
    }

    @Test
    fun `a body with no thought is returned untouched`() {
        val body = "→ tool_call end_session({\"outcome\":\"success\"})"
        val split = splitInlineThought(body)

        assertNull(split.reasoning)
        assertEquals(body, split.body)
    }

    @Test
    fun `a multi-line thought is captured whole`() {
        val body = "<thought>Step one.\n\nStep two: tap it.</thought>answer"
        val split = splitInlineThought(body)

        assertEquals("Step one.\n\nStep two: tap it.", split.reasoning)
        assertEquals("answer", split.body)
    }

    @Test
    fun `the thought is not duplicated into both fields`() {
        val split = splitInlineThought("<thought>secret plan</thought>do the thing")
        assertTrue("reasoning holds it", split.reasoning!!.contains("secret plan"))
        assertTrue("body must not repeat it", !split.body.contains("secret plan"))
    }

    @Test
    fun `an unterminated thought tag is left alone rather than swallowing the answer`() {
        // Truncation (finish_reason=length) can cut the closing tag; dropping everything after
        // an opening tag would silently delete the response.
        val body = "<thought>I was cut off mid-thou"
        val split = splitInlineThought(body)

        assertNull(split.reasoning)
        assertEquals(body, split.body)
    }

    @Test
    fun `an empty thought yields no reasoning`() {
        val split = splitInlineThought("<thought>  </thought>answer")
        assertNull(split.reasoning)
        assertEquals("answer", split.body)
    }
}
