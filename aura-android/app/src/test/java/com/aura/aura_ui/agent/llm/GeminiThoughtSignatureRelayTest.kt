package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the wire-level relay that round-trips Gemini 3's per-tool-call
 * `extra_content.google.thought_signature` through Koog's OpenAI-compat client.
 *
 * The two transforms are pure (no I/O), so they're driven directly with the exact
 * JSON shapes Gemini emits on the `/v1beta/openai/chat/completions` surface.
 */
class GeminiThoughtSignatureRelayTest {

    private val responseWithSignature = """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "tool_calls": [
                  {
                    "id": "call_abc",
                    "type": "function",
                    "function": { "name": "lookup_app", "arguments": "{\"app_name\":\"Apple Music\"}" },
                    "extra_content": { "google": { "thought_signature": "SIG_XYZ" } }
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
            { "role": "user", "content": "open apple music" },
            {
              "role": "assistant",
              "tool_calls": [
                {
                  "id": "$id",
                  "type": "function",
                  "function": { "name": "lookup_app", "arguments": "{\"app_name\":\"Apple Music\"}" }
                }
              ]
            },
            { "role": "tool", "tool_call_id": "$id", "content": "{\"found\":true}" }
          ]
        }
    """.trimIndent()

    @Test
    fun `extracts thought signature keyed by tool call id`() {
        val sigs = GeminiThoughtSignatureRelay.extractToolCallSignatures(responseWithSignature)
        assertEquals(setOf("call_abc"), sigs.keys)
        assertTrue("must retain the signature payload", sigs.getValue("call_abc").toString().contains("SIG_XYZ"))
    }

    @Test
    fun `extract fails open on malformed body`() {
        assertTrue(GeminiThoughtSignatureRelay.extractToolCallSignatures("not json").isEmpty())
        assertTrue(GeminiThoughtSignatureRelay.extractToolCallSignatures("").isEmpty())
    }

    @Test
    fun `extract ignores tool calls without a signature`() {
        val noSig = """{"choices":[{"message":{"tool_calls":[{"id":"x","function":{"name":"f"}}]}}]}"""
        assertTrue(GeminiThoughtSignatureRelay.extractToolCallSignatures(noSig).isEmpty())
    }

    @Test
    fun `injects signature into matching assistant tool call`() {
        val sigs = GeminiThoughtSignatureRelay.extractToolCallSignatures(responseWithSignature)
        val out = GeminiThoughtSignatureRelay.injectToolCallSignatures(assistantReplayRequest("call_abc"), sigs)
        requireNotNull(out) { "request must be rewritten when a signature applies" }
        assertTrue("request must carry the echoed signature", out.contains("SIG_XYZ"))
        assertTrue("signature must live under google.thought_signature", out.contains("thought_signature"))
    }

    @Test
    fun `inject returns null when no tool call id matches`() {
        val sigs = GeminiThoughtSignatureRelay.extractToolCallSignatures(responseWithSignature)
        // Request replays a DIFFERENT id → nothing to inject.
        assertNull(GeminiThoughtSignatureRelay.injectToolCallSignatures(assistantReplayRequest("call_other"), sigs))
    }

    @Test
    fun `inject returns null when there are no signatures`() {
        assertNull(GeminiThoughtSignatureRelay.injectToolCallSignatures(assistantReplayRequest("call_abc"), emptyMap()))
    }

    @Test
    fun `inject does not clobber an existing extra_content`() {
        val sigs = GeminiThoughtSignatureRelay.extractToolCallSignatures(responseWithSignature)
        val alreadyHasIt = """
            {"messages":[{"role":"assistant","tool_calls":[
              {"id":"call_abc","function":{"name":"lookup_app"},
               "extra_content":{"google":{"thought_signature":"ALREADY"}}}
            ]}]}
        """.trimIndent()
        // Present already → leave it untouched (no rewrite).
        assertNull(GeminiThoughtSignatureRelay.injectToolCallSignatures(alreadyHasIt, sigs))
    }

    @Test
    fun `stateful round trip via capture then inject`() {
        GeminiThoughtSignatureRelay.captureFromResponse(responseWithSignature)
        val out = GeminiThoughtSignatureRelay.injectIntoRequest(assistantReplayRequest("call_abc"))
        requireNotNull(out) { "captured signature must be replayed on the next request" }
        assertTrue(out.contains("SIG_XYZ"))
    }
}
