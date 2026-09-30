package com.aura.aura_ui.agent.llm

import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** Verifies the `include_thoughts` injection shape (uses org.json → Robolectric). */
@RunWith(RobolectricTestRunner::class)
class GeminiThinkingConfigTest {

    @After
    fun resetTap() {
        AgentLlmTap.sink = null
    }

    // ── B13: interceptor is trace-gated ─────────────────────────────────────
    // Thought summaries exist only for the agent trace. Without a trace logger
    // listening, injecting thinking_config leaks reasoning into user-facing replies.

    @Test
    fun `interceptor does not inject thinking_config when no trace logger is active`() {
        AgentLlmTap.sink = null
        val chain = FakeChain(geminiChatRequest())
        GeminiThinkingConfig.interceptor().intercept(chain)
        assertFalse(
            "request body must be untouched when the trace tap is inactive",
            "thinking_config" in chain.proceededBodyText(),
        )
    }

    @Test
    fun `interceptor injects thinking_config when a trace logger is active`() {
        AgentLlmTap.sink = { }
        val chain = FakeChain(geminiChatRequest())
        GeminiThinkingConfig.interceptor().intercept(chain)
        assertTrue(
            "request body must carry thinking_config when the trace tap is active",
            "thinking_config" in chain.proceededBodyText(),
        )
    }

    private fun geminiChatRequest(): Request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
        .post("""{"model":"gemini-3-flash","messages":[]}""".toRequestBody("application/json".toMediaType()))
        .build()

    /** Minimal chain that records the request the interceptor forwarded. */
    private class FakeChain(private val request: Request) : Interceptor.Chain {
        private var proceeded: Request? = null

        fun proceededBodyText(): String {
            val body = requireNotNull(proceeded) { "interceptor never called proceed()" }.body
            return Buffer().also { requireNotNull(body).writeTo(it) }.readUtf8()
        }

        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): okhttp3.Call = throw UnsupportedOperationException("not used")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun `adds both thinking_level and include_thoughts under extra_body google thinking_config`() {
        val out = GeminiThinkingConfig.withIncludeThoughts("""{"model":"gemini-3.1-flash-lite","messages":[]}""")
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertTrue("include_thoughts must be true", tc.getBoolean("include_thoughts"))
        assertTrue("thinking_level must be low", tc.getString("thinking_level") == "low")
    }

    @Test
    fun `gemini 2_5 uses thinking_budget not thinking_level (avoids the 400)`() {
        val out = GeminiThinkingConfig.withIncludeThoughts("""{"model":"gemini-2.5-flash","messages":[]}""")
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertTrue("include_thoughts must be set", tc.getBoolean("include_thoughts"))
        assertTrue("2.5 enables thinking via a positive budget", tc.getInt("thinking_budget") > 0)
        assertFalse("2.5 must NOT carry thinking_level", tc.has("thinking_level"))
    }

    @Test
    fun `returns null when both thinking_level and include_thoughts are already set (idempotent)`() {
        val already = """{"extra_body":{"google":{"thinking_config":{"thinking_level":"low","include_thoughts":true}}}}"""
        assertNull(GeminiThinkingConfig.withIncludeThoughts(already))
    }

    @Test
    fun `adds thinking_level if only include_thoughts is present`() {
        val input = """{"extra_body":{"google":{"thinking_config":{"include_thoughts":true}}}}"""
        val out = GeminiThinkingConfig.withIncludeThoughts(input)
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertTrue("keeps include_thoughts", tc.getBoolean("include_thoughts"))
        assertTrue("adds thinking_level", tc.getString("thinking_level") == "low")
    }

    @Test
    fun `preserves and completes an existing extra_body google sibling`() {
        val input = """{"extra_body":{"google":{"thinking_config":{"thinking_level":"low"}}}}"""
        val out = GeminiThinkingConfig.withIncludeThoughts(input)
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertTrue("keeps thinking_level", tc.getString("thinking_level") == "low")
        assertTrue("adds include_thoughts", tc.getBoolean("include_thoughts"))
    }

    @Test
    fun `creates entire extra_body structure when absent`() {
        val minimal = """{"model":"gemini-3.1-flash-lite"}"""
        val out = GeminiThinkingConfig.withIncludeThoughts(minimal)
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertTrue(tc.getBoolean("include_thoughts"))
        assertTrue(tc.getString("thinking_level") == "low")
    }

    @Test
    fun `adds include_thoughts if only thinking_level is present`() {
        val input = """{"extra_body":{"google":{"thinking_config":{"thinking_level":"low"}}}}"""
        val out = GeminiThinkingConfig.withIncludeThoughts(input)
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertTrue("keeps thinking_level", tc.getString("thinking_level") == "low")
        assertTrue("adds include_thoughts", tc.getBoolean("include_thoughts"))
    }

    @Test
    fun `fails open on malformed json`() {
        assertNull(GeminiThinkingConfig.withIncludeThoughts("not json"))
    }
}
