package com.aura.aura_ui.agent.llm

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Trace-v2 behavior of [AgentLlmTap]: split the system block out (so the logger can
 * store it once), keep only the per-turn delta as `prompt`, and surface
 * reasoning + finish_reason. Uses Robolectric because the tap parses with `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class AgentLlmTapTest {

    @After fun tearDown() { AgentLlmTap.sink = null }

    private fun capture(requestBody: String, responseBody: String): AgentLlmTap.Call {
        var captured: AgentLlmTap.Call? = null
        AgentLlmTap.sink = { captured = it }
        AgentLlmTap.report("generativelanguage.googleapis.com", requestBody, responseBody, durationMs = 42)
        return requireNotNull(captured) { "tap did not emit a Call" }
    }

    @Test
    fun `turn 0 - system split out, delta is the user goal`() {
        val req = """
            {"model":"gemini-3.1-flash-lite","messages":[
              {"role":"system","content":"You are AURA. Doctrine here."},
              {"role":"user","content":"Open Apple Music"}
            ]}
        """.trimIndent()
        val resp = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""

        val call = capture(req, resp)
        assertEquals("You are AURA. Doctrine here.", call.systemPrompt)
        assertTrue("delta should carry the user goal", call.prompt.contains("Open Apple Music"))
        assertTrue("delta must NOT repeat the system doctrine", !call.prompt.contains("Doctrine here"))
        assertEquals("stop", call.finishReason)
    }

    @Test
    fun `later turn - delta is only the newest tool result, not whole history`() {
        val req = """
            {"model":"gemini-3.1-flash-lite","messages":[
              {"role":"system","content":"You are AURA. Doctrine here."},
              {"role":"user","content":"Open Apple Music"},
              {"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"lookup_app","arguments":"{}"}}]},
              {"role":"tool","tool_call_id":"c1","content":"{\"found\":true,\"package\":\"com.apple.music\"}"}
            ]}
        """.trimIndent()
        val resp = """{"choices":[{"message":{"content":"","tool_calls":[{"function":{"name":"launch_app","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""

        val call = capture(req, resp)
        assertTrue("delta should be the newest tool result", call.prompt.contains("com.apple.music"))
        assertTrue("delta must not repeat the original goal", !call.prompt.contains("Open Apple Music"))
        assertTrue("delta must not repeat doctrine", !call.prompt.contains("Doctrine here"))
        assertEquals("You are AURA. Doctrine here.", call.systemPrompt)
        assertTrue("response shows the chosen tool", call.response.contains("launch_app"))
        assertEquals("tool_calls", call.finishReason)
    }

    @Test
    fun `reasoning_content is captured when present`() {
        val req = """{"messages":[{"role":"user","content":"hi"}]}"""
        val resp = """{"choices":[{"message":{"content":"hello","reasoning_content":"the user greeted me"}}]}"""
        val call = capture(req, resp)
        assertEquals("the user greeted me", call.reasoning)
    }

    @Test
    fun `reasoning is null when the model does not emit it`() {
        val req = """{"messages":[{"role":"user","content":"hi"}]}"""
        val resp = """{"choices":[{"message":{"content":"hello"}}]}"""
        assertNull(capture(req, resp).reasoning)
    }

    @Test
    fun `image parts are elided to a placeholder in the delta`() {
        val req = """
            {"messages":[
              {"role":"user","content":[
                {"type":"text","text":"look"},
                {"type":"image_url","image_url":{"url":"data:image/png;base64,${"A".repeat(4096)}"}}
              ]}
            ]}
        """.trimIndent()
        val call = capture(req, """{"choices":[{"message":{"content":"ok"}}]}""")
        assertTrue("image must be elided, not inlined", call.prompt.contains("[image ~"))
        assertNotNull(call.prompt)
    }

    @Test
    fun `reasoning_content is captured from Gemini thinking response`() {
        val req = """
            {"model":"gemini-3.1-flash-lite","messages":[
              {"role":"system","content":"Help find UI elements"},
              {"role":"user","content":"Click the Play button"}
            ]}
        """.trimIndent()
        val resp = """
            {"choices":[{
              "message":{
                "content":"I can see the Play button in the top-right corner. Tapping it now.",
                "reasoning_content":"The user wants me to click Play. Looking at the screenshot, I see a triangular play icon in the top-right area at coordinates..."
              },
              "finish_reason":"stop"
            }]}
        """.trimIndent()
        val call = capture(req, resp)
        assertEquals("Gemini reasoning should be captured",
            "The user wants me to click Play. Looking at the screenshot, I see a triangular play icon in the top-right area at coordinates...",
            call.reasoning)
    }

    @Test
    fun `reasoning field is used as fallback when reasoning_content is absent`() {
        val req = """{"model":"gpt-o1","messages":[{"role":"user","content":"think hard"}]}"""
        val resp = """
            {"choices":[{
              "message":{
                "content":"The answer is 42",
                "reasoning":"Let me think through this problem step by step..."
              },
              "finish_reason":"stop"
            }]}
        """.trimIndent()
        val call = capture(req, resp)
        assertEquals("OpenAI reasoning field should be captured as fallback",
            "Let me think through this problem step by step...",
            call.reasoning)
    }

    @Test
    fun `reasoning_content takes precedence over reasoning when both present`() {
        val req = """{"model":"hybrid-model","messages":[{"role":"user","content":"test"}]}"""
        val resp = """
            {"choices":[{
              "message":{
                "content":"answer",
                "reasoning_content":"Preferred Gemini thinking text",
                "reasoning":"Legacy reasoning field"
              },
              "finish_reason":"stop"
            }]}
        """.trimIndent()
        val call = capture(req, resp)
        assertEquals("reasoning_content should be preferred",
            "Preferred Gemini thinking text",
            call.reasoning)
    }

    // ── Token usage — must not assume one vendor's field names (AURA talks to any
    // OpenAI-compat endpoint: Groq, OpenRouter, Gemini's compat layer, user-added
    // custom endpoints). Each provider spells cache/reasoning accounting differently. ──

    @Test
    fun `cached tokens parsed from OpenAI Gemini-compat nested shape`() {
        val usage = JSONObject(
            """{"prompt_tokens":17429,"completion_tokens":22,"total_tokens":17451,
                "prompt_tokens_details":{"cached_tokens":15000}}""",
        )
        val parsed = AgentLlmTap.parseTokenUsage(usage)
        assertEquals(15000, parsed.cachedTokens)
        assertEquals(17429, parsed.promptTokens)
    }

    @Test
    fun `cached tokens parsed from DeepSeek top-level shape`() {
        val usage = JSONObject(
            """{"prompt_tokens":5000,"completion_tokens":30,"total_tokens":5030,
                "prompt_cache_hit_tokens":4200,"prompt_cache_miss_tokens":800}""",
        )
        assertEquals(4200, AgentLlmTap.parseTokenUsage(usage).cachedTokens)
    }

    @Test
    fun `cached tokens parsed from Anthropic-shaped proxy usage`() {
        val usage = JSONObject(
            """{"prompt_tokens":9000,"completion_tokens":40,"total_tokens":9040,
                "cache_read_input_tokens":8000,"cache_creation_input_tokens":500}""",
        )
        // cache_creation is the cost of WRITING the cache, not a hit — must not be
        // mistaken for cachedTokens, or a cold cache-warming call would misreport as efficient.
        assertEquals(8000, AgentLlmTap.parseTokenUsage(usage).cachedTokens)
    }

    @Test
    fun `cached and reasoning tokens are null when the provider reports neither`() {
        val usage = JSONObject("""{"prompt_tokens":1000,"completion_tokens":50,"total_tokens":1050}""")
        val parsed = AgentLlmTap.parseTokenUsage(usage)
        assertNull("absent field must stay null, never guessed as 0", parsed.cachedTokens)
        assertNull(parsed.reasoningTokens)
        assertEquals(1000, parsed.promptTokens)
    }

    @Test
    fun `reasoning tokens parsed from completion_tokens_details`() {
        val usage = JSONObject(
            """{"prompt_tokens":2000,"completion_tokens":300,"total_tokens":2300,
                "completion_tokens_details":{"reasoning_tokens":250}}""",
        )
        assertEquals(250, AgentLlmTap.parseTokenUsage(usage).reasoningTokens)
    }

    @Test
    fun `null usage object yields all-null TokenUsage, not a crash`() {
        val parsed = AgentLlmTap.parseTokenUsage(null)
        assertNull(parsed.promptTokens)
        assertNull(parsed.cachedTokens)
        assertNull(parsed.reasoningTokens)
    }

    @Test
    fun `cached tokens flow end-to-end through report() into Call`() {
        val req = """{"model":"gemini-3.1-flash-lite","messages":[{"role":"user","content":"hi"}]}"""
        val resp = """
            {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":17429,"completion_tokens":22,"total_tokens":17451,
                      "prompt_tokens_details":{"cached_tokens":15000}}}
        """.trimIndent()
        val call = capture(req, resp)
        assertEquals(15000, call.cachedTokens)
        assertEquals(17429, call.promptTokens)
    }

    /**
     * Captured from the device 2026-09-22. Gemini answered a chat call with its error wrapped in a
     * JSON **array**, not an object — `[{"error":{...}}]`. `report()` parsed the body with
     * `JSONObject(responseBody)`, which throws `JSONException: ... of type JSONArray cannot be
     * converted to JSONObject`, so the 503 surfaced only as "Failed to parse token usage for
     * budget" and the real reason ("this model is experiencing high demand") never reached the
     * user — who saw "I couldn't reach your AI provider" and went looking for a code regression.
     */
    private val arrayWrappedError =
        """[{"error":{"code":503,"message":"This model is currently experiencing high demand. """ +
            """Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}]"""

    @Test
    fun `an array-wrapped provider error does not throw while parsing usage`() {
        var usage: AgentLlmTap.TokenUsage? = null
        var threw: Throwable? = null
        AgentLlmTap.usageSink = { usage = it }
        try {
            AgentLlmTap.report(
                "generativelanguage.googleapis.com",
                """{"model":"gemini-3.5-flash-lite","messages":[{"role":"user","content":"hi"}]}""",
                arrayWrappedError,
                durationMs = 15131,
            )
        } catch (t: Throwable) {
            threw = t
        } finally {
            AgentLlmTap.usageSink = null
        }
        assertNull("report() must not propagate a parse failure: $threw", threw)
        // An error body carries no usage — the point is that it degrades instead of throwing.
        assertTrue("no usage should be reported for an error body", usage == null || usage!!.promptTokens == null)
    }

    @Test
    fun `an array-wrapped body still yields the provider error text`() {
        val call = capture(
            """{"model":"gemini-3.5-flash-lite","messages":[{"role":"user","content":"hi"}]}""",
            arrayWrappedError,
        )
        assertTrue(
            "the provider's own reason must survive into the trace: ${call.response}",
            call.response.contains("high demand"),
        )
    }

    @Test
    fun `the full bodies pass through untouched`() {
        val req = """{"model":"m","messages":[{"role":"user","content":"hi"}]}"""
        val resp = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
        val call = capture(req, resp)
        assertEquals(req, call.requestBody)
        assertEquals(resp, call.responseBody)
        assertNull(call.purpose)
    }

    @Test
    fun `the research phrase call is tagged and kept out of the run budget`() {
        var usage: AgentLlmTap.TokenUsage? = null
        AgentLlmTap.usageSink = { usage = it }
        try {
            val req = JSONObject().put("model", "m").put(
                "messages",
                org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", com.aura.aura_ui.agent.research.ResearchText.PHRASE_INSTRUCTION))
                    .put(JSONObject().put("role", "user").put("content", "text Amma I am late")),
            ).toString()
            val resp = """{"choices":[{"message":{"content":"send a message"}}],"usage":{"prompt_tokens":50,"completion_tokens":4,"total_tokens":54}}"""
            val call = capture(req, resp)
            assertEquals(AgentLlmTap.PURPOSE_RESEARCH_PHRASE, call.purpose)
            assertNull("the side call must not spend the run's budget", usage)
        } finally {
            AgentLlmTap.usageSink = null
        }
    }

    @Test
    fun `the research page-reading call is tagged too`() {
        // F9: it was logged as an agent turn, so tool calls were credited to it.
        val req = JSONObject().put("model", "m").put(
            "messages",
            org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", com.aura.aura_ui.agent.research.ResearchText.EXTRACT_INSTRUCTION))
                .put(JSONObject().put("role", "user").put("content", "page text")),
        ).toString()
        val call = capture(req, """{"choices":[{"message":{"content":"NONE"}}]}""")
        assertEquals(AgentLlmTap.PURPOSE_RESEARCH_READ, call.purpose)
    }
}
