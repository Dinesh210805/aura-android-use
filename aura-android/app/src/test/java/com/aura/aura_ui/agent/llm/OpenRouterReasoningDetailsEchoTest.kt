package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the wire-level echo of OpenRouter's message-level `reasoning_details`
 * (openrouter.ai/docs/guides/best-practices/reasoning-tokens): reasoning models pause on a
 * tool call and REQUIRE the reasoning_details array passed back unmodified on the matching
 * assistant message, or the continuation degrades/fails (Gemini 3 via OpenRouter 400s).
 *
 * Mirrors [GeminiThoughtSignatureRelayTest] — the transforms are pure, driven with the
 * exact JSON shapes OpenRouter emits on `/api/v1/chat/completions`.
 */
class OpenRouterReasoningDetailsEchoTest {

    private val responseWithDetails = """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": null,
                "tool_calls": [
                  {
                    "id": "call_or1",
                    "type": "function",
                    "function": { "name": "perceive_screen", "arguments": "{}" }
                  }
                ],
                "reasoning_details": [
                  {
                    "type": "reasoning.encrypted",
                    "data": "ENCRYPTED_BLOB_A",
                    "id": "rd-1",
                    "format": "google-gemini-v1",
                    "index": 0
                  }
                ]
              }
            }
          ]
        }
    """.trimIndent()

    private fun assistantReplayRequest(id: String) = """
        {
          "messages": [
            { "role": "user", "content": "open settings" },
            {
              "role": "assistant",
              "content": null,
              "tool_calls": [
                {
                  "id": "$id",
                  "type": "function",
                  "function": { "name": "perceive_screen", "arguments": "{}" }
                }
              ]
            },
            { "role": "tool", "tool_call_id": "$id", "content": "{\"elements\":[]}" }
          ]
        }
    """.trimIndent()

    @Test
    fun `extracts reasoning_details keyed by first tool call id`() {
        val details = OpenRouterReasoningDetailsEcho.extractReasoningDetails(responseWithDetails)
        assertEquals(setOf("call_or1"), details.keys)
        assertTrue(
            "must retain the reasoning payload byte-identical",
            details.getValue("call_or1").toString().contains("ENCRYPTED_BLOB_A"),
        )
    }

    @Test
    fun `extract fails open on malformed body`() {
        assertTrue(OpenRouterReasoningDetailsEcho.extractReasoningDetails("not json").isEmpty())
        assertTrue(OpenRouterReasoningDetailsEcho.extractReasoningDetails("").isEmpty())
    }

    @Test
    fun `extract ignores messages without tool calls`() {
        // Final-answer messages carry reasoning_details too, but with no tool call there is
        // no stable key AND no continuation that needs them — skip.
        val finalAnswer = """
            {"choices":[{"message":{"role":"assistant","content":"done",
              "reasoning_details":[{"type":"reasoning.text","text":"t","index":0}]}}]}
        """.trimIndent()
        assertTrue(OpenRouterReasoningDetailsEcho.extractReasoningDetails(finalAnswer).isEmpty())
    }

    @Test
    fun `extract ignores messages with empty reasoning_details`() {
        val empty = """
            {"choices":[{"message":{"tool_calls":[{"id":"x","function":{"name":"f"}}],
              "reasoning_details":[]}}]}
        """.trimIndent()
        assertTrue(OpenRouterReasoningDetailsEcho.extractReasoningDetails(empty).isEmpty())
    }

    @Test
    fun `injects details into matching assistant message`() {
        val details = OpenRouterReasoningDetailsEcho.extractReasoningDetails(responseWithDetails)
        val out = OpenRouterReasoningDetailsEcho.injectReasoningDetails(assistantReplayRequest("call_or1"), details)
        requireNotNull(out) { "request must be rewritten when details apply" }
        assertTrue("request must carry the echoed details", out.contains("ENCRYPTED_BLOB_A"))
        assertTrue("details must live under reasoning_details", out.contains("reasoning_details"))
    }

    @Test
    fun `inject returns null when no tool call id matches`() {
        val details = OpenRouterReasoningDetailsEcho.extractReasoningDetails(responseWithDetails)
        assertNull(OpenRouterReasoningDetailsEcho.injectReasoningDetails(assistantReplayRequest("call_other"), details))
    }

    @Test
    fun `inject returns null when there are no details`() {
        assertNull(OpenRouterReasoningDetailsEcho.injectReasoningDetails(assistantReplayRequest("call_or1"), emptyMap()))
    }

    @Test
    fun `inject does not clobber existing reasoning_details`() {
        val details = OpenRouterReasoningDetailsEcho.extractReasoningDetails(responseWithDetails)
        val alreadyHasIt = """
            {"messages":[{"role":"assistant","tool_calls":[
              {"id":"call_or1","function":{"name":"perceive_screen"}}],
              "reasoning_details":[{"type":"reasoning.text","text":"ALREADY","index":0}]}]}
        """.trimIndent()
        assertNull(OpenRouterReasoningDetailsEcho.injectReasoningDetails(alreadyHasIt, details))
    }

    @Test
    fun `stateful round trip via capture then inject`() {
        OpenRouterReasoningDetailsEcho.captureFromResponse(responseWithDetails)
        val out = OpenRouterReasoningDetailsEcho.injectIntoRequest(assistantReplayRequest("call_or1"))
        requireNotNull(out) { "captured details must be replayed on the next request" }
        assertTrue(out.contains("ENCRYPTED_BLOB_A"))
    }
}
