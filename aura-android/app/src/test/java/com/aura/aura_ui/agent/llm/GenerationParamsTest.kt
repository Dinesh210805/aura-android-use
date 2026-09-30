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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * The wire mapping of user generation controls (D2 piece 2). These tests ARE the spec of each
 * provider's reasoning dialect + the byte-identical-when-default invariant. Uses org.json → Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class GenerationParamsTest {

    private val body = """{"model":"m","messages":[]}"""

    // ── The safety invariant: an untouched config never changes the request. ──

    @Test
    fun `default config is a no-op for every style`() {
        for (style in ReasoningRequestStyle.entries) {
            assertNull("$style must be untouched by a default config", GenerationParams.apply(body, style, GenerationConfig()))
        }
    }

    @Test
    fun `malformed json fails open`() {
        assertNull(GenerationParams.apply("not json", ReasoningRequestStyle.OPENROUTER_REASONING, GenerationConfig(temperature = 0.2)))
    }

    // ── Temperature (universal, replace-or-insert). ──

    @Test
    fun `temperature is inserted when absent`() {
        val out = GenerationParams.apply(body, ReasoningRequestStyle.NONE, GenerationConfig(temperature = 0.2))
        requireNotNull(out)
        assertEquals(0.2, JSONObject(out).getDouble("temperature"), 0.0001)
    }

    @Test
    fun `temperature replaces a value Koog already emitted`() {
        val withTemp = """{"model":"m","temperature":1.0,"messages":[]}"""
        val out = GenerationParams.apply(withTemp, ReasoningRequestStyle.NONE, GenerationConfig(temperature = 0.2))
        requireNotNull(out)
        assertEquals("replaced, not duplicated", 0.2, JSONObject(out).getDouble("temperature"), 0.0001)
    }

    // ── OpenAI / Groq reasoning models: top-level reasoning_effort. ──

    @Test
    fun `openai effort maps HIGH to reasoning_effort high`() {
        val out = GenerationParams.apply(body, ReasoningRequestStyle.OPENAI_REASONING_EFFORT, GenerationConfig(reasoning = ReasoningLevel.HIGH))
        requireNotNull(out)
        assertEquals("high", JSONObject(out).getString("reasoning_effort"))
    }

    @Test
    fun `openai effort has no off — OFF sends nothing`() {
        // The OpenAI chat API cannot disable reasoning; OFF must fall through to provider default.
        assertNull(GenerationParams.apply(body, ReasoningRequestStyle.OPENAI_REASONING_EFFORT, GenerationConfig(reasoning = ReasoningLevel.OFF)))
    }

    // ── OpenRouter unified reasoning param. ──

    @Test
    fun `openrouter OFF disables reasoning`() {
        val out = GenerationParams.apply(body, ReasoningRequestStyle.OPENROUTER_REASONING, GenerationConfig(reasoning = ReasoningLevel.OFF))
        requireNotNull(out)
        assertFalse(JSONObject(out).getJSONObject("reasoning").getBoolean("enabled"))
    }

    @Test
    fun `openrouter MEDIUM sets reasoning effort`() {
        val out = GenerationParams.apply(body, ReasoningRequestStyle.OPENROUTER_REASONING, GenerationConfig(reasoning = ReasoningLevel.MEDIUM))
        requireNotNull(out)
        assertEquals("medium", JSONObject(out).getJSONObject("reasoning").getString("effort"))
    }

    // ── Gemini thinking_config (extra_body.google). ──

    // Gemini's OpenAI-compat thinking field is generation-specific (verified live 2026-07-13):
    // 3.x → thinking_level, 2.5-era → integer thinking_budget. Sending the wrong one 400s.
    private val gemini25 = """{"model":"gemini-2.5-flash","messages":[]}"""
    private val gemini3 = """{"model":"gemini-3-flash-preview","messages":[]}"""

    @Test
    fun `gemini 3 LOW sets thinking_level low`() {
        val out = GenerationParams.apply(gemini3, ReasoningRequestStyle.GEMINI_THINKING_CONFIG, GenerationConfig(reasoning = ReasoningLevel.LOW))
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertEquals("low", tc.getString("thinking_level"))
    }

    @Test
    fun `gemini 3 HIGH sets thinking_level high`() {
        val out = GenerationParams.apply(gemini3, ReasoningRequestStyle.GEMINI_THINKING_CONFIG, GenerationConfig(reasoning = ReasoningLevel.HIGH))
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertEquals("high", tc.getString("thinking_level"))
    }

    @Test
    fun `gemini 2_5 LOW sets a positive thinking_budget, not a level`() {
        val out = GenerationParams.apply(gemini25, ReasoningRequestStyle.GEMINI_THINKING_CONFIG, GenerationConfig(reasoning = ReasoningLevel.LOW))
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertTrue("2.5 must use an integer budget", tc.getInt("thinking_budget") > 0)
        assertFalse("2.5 must NOT send thinking_level (400s)", tc.has("thinking_level"))
    }

    @Test
    fun `gemini 2_5 OFF zeroes the thinking budget`() {
        val out = GenerationParams.apply(gemini25, ReasoningRequestStyle.GEMINI_THINKING_CONFIG, GenerationConfig(reasoning = ReasoningLevel.OFF))
        requireNotNull(out)
        val tc = JSONObject(out).getJSONObject("extra_body").getJSONObject("google").getJSONObject("thinking_config")
        assertEquals(0, tc.getInt("thinking_budget"))
    }

    // ── Combined + interceptor smoke. ──

    @Test
    fun `temperature and reasoning combine in one request`() {
        val out = GenerationParams.apply(
            body,
            ReasoningRequestStyle.OPENROUTER_REASONING,
            GenerationConfig(reasoning = ReasoningLevel.HIGH, temperature = 0.1),
        )
        requireNotNull(out)
        val json = JSONObject(out)
        assertEquals(0.1, json.getDouble("temperature"), 0.0001)
        assertEquals("high", json.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun `interceptor rewrites the outgoing body`() {
        val chain = FakeChain(
            Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        )
        GenerationParams.interceptor(ReasoningRequestStyle.OPENROUTER_REASONING, GenerationConfig(reasoning = ReasoningLevel.OFF))
            .intercept(chain)
        assertTrue("reasoning" in chain.proceededBodyText())
    }

    @Test
    fun `interceptor leaves the body untouched for a default config`() {
        val chain = FakeChain(
            Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        )
        GenerationParams.interceptor(ReasoningRequestStyle.OPENROUTER_REASONING, GenerationConfig()).intercept(chain)
        assertFalse("reasoning" in chain.proceededBodyText())
    }

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
}
