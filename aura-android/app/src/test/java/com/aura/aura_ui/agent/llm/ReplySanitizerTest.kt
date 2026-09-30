package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B13: model reasoning must never reach the chat bubble or TTS. The final reply
 * is sanitized at the agent boundary; reasoning belongs to the trace only
 * ([AgentLlmTap.Call.reasoning]).
 */
class ReplySanitizerTest {

    @Test
    fun `passes through a reply without thinking tags unchanged`() {
        assertEquals(
            "Opened WhatsApp and sent the message.",
            ReplySanitizer.stripThinking("Opened WhatsApp and sent the message."),
        )
    }

    @Test
    fun `strips a think block and returns only the answer`() {
        val raw = "<think>The user wants X. I should tap Y first.</think>Done — I tapped Y."
        assertEquals("Done — I tapped Y.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `strips a multiline think block`() {
        val raw = "<think>\nStep 1: look at screen.\nStep 2: decide.\n</think>\nAll set."
        assertEquals("All set.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `strips multiple think blocks`() {
        val raw = "<think>first</think>Part one. <think>second</think>Part two."
        assertEquals("Part one. Part two.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `strips the thinking tag variant`() {
        val raw = "<thinking>internal</thinking>The answer is 42."
        assertEquals("The answer is 42.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `tag matching is case-insensitive`() {
        val raw = "<Think>internal</THINK>Done."
        assertEquals("Done.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `drops leading reasoning when only a closing tag is present`() {
        // DeepSeek-R1-style serving quirk: the opening <think> is consumed by the
        // provider, content arrives as "reasoning…</think>answer".
        val raw = "The user asked for the weather, I checked the widget.</think>It's 24°C and sunny."
        assertEquals("It's 24°C and sunny.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `strips an unclosed think block to the end`() {
        // Truncated generation: opening tag, no closing tag — nothing after it is an answer.
        val raw = "Here you go.<think>but wait, maybe I should"
        assertEquals("Here you go.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `returns empty when the reply is only thinking`() {
        assertEquals("", ReplySanitizer.stripThinking("<think>only internal monologue</think>"))
    }

    @Test
    fun `does not touch prose that merely mentions the word think`() {
        val raw = "I think the meeting is at 3pm."
        assertEquals(raw, ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `trims whitespace left behind by stripping`() {
        val raw = "  <think>x</think>   Answer.  "
        assertEquals("Answer.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `strips a gemini thought block`() {
        // Gemini 3.x on the OpenAI-compat surface emits <thought>…</thought>
        // (observed in on-device run 1783008241421-9b007b4e).
        val raw = "<thought>**Troubleshooting** the search failed after three attempts.</thought>Retrying now."
        assertEquals("Retrying now.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `returns empty when the reply is only a thought block`() {
        assertEquals("", ReplySanitizer.stripThinking("<thought>only internal monologue</thought>"))
    }

    @Test
    fun `strips an unclosed thought block to the end`() {
        val raw = "Done.<thought>but maybe I should"
        assertEquals("Done.", ReplySanitizer.stripThinking(raw))
    }

    @Test
    fun `does not touch prose that merely mentions the word thought`() {
        val raw = "I thought the meeting was at 3pm."
        assertEquals(raw, ReplySanitizer.stripThinking(raw))
    }
}
