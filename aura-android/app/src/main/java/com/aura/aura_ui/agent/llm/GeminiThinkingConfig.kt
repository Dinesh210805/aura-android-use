package com.aura.aura_ui.agent.llm

import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject

/**
 * Asks Gemini for **thought summaries** so the trace can show the model's
 * reasoning. On the OpenAI-compat surface this is requested via a top-level
 * `extra_body.google.thinking_config` with:
 * - an enabling field — `thinking_level = "low"` on Gemini 3.x, or a positive
 *   `thinking_budget` on Gemini 2.5-era (the field is generation-specific; sending
 *   the wrong one 400s — see [GeminiThinkingWire]).
 * - `include_thoughts = true` (ensures thought summaries appear in response).
 * (per ai.google.dev/gemini-api/docs/openai and the thinking guide).
 * Both an enabling field and `include_thoughts` are necessary.
 *
 * Gemini-host only; additive (never overwrites an existing `extra_body`/config);
 * fails open. The encrypted `thought_signature` is a separate, internal concern —
 * see [GeminiThoughtSignatureRelay].
 *
 * **Trace-gated (B13):** thought summaries exist solely for the agent trace.
 * Injecting them unconditionally made Gemini prepend reasoning to user-facing
 * replies (shown AND spoken in STT/TTS chat), so the config is only requested
 * while [AgentLlmTap] has an active sink — i.e. a trace logger is listening.
 */
internal object GeminiThinkingConfig {

    private const val GEMINI_HOST = "generativelanguage.googleapis.com"

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!AgentLlmTap.isActive) return@Interceptor chain.proceed(request)
        if (request.url.host != GEMINI_HOST) return@Interceptor chain.proceed(request)
        val patched = runCatching {
            val body = request.body ?: return@runCatching request
            val mediaType = body.contentType()
            if (mediaType?.subtype?.contains("json", ignoreCase = true) != true) return@runCatching request
            val text = Buffer().also { body.writeTo(it) }.readUtf8()
            val newText = withIncludeThoughts(text) ?: return@runCatching request
            request.newBuilder().method(request.method, newText.toRequestBody(mediaType)).build()
        }.getOrDefault(request)
        chain.proceed(patched)
    }

    /**
     * Ensure `extra_body.google.thinking_config` has both:
     * - `thinking_level = "low"` (enables thinking; "low" is cheapest while still producing summaries)
     * - `include_thoughts = true` (ensures thought summaries appear in the response)
     *
     * Returns the rewritten body, or null if it was already set (nothing to change) or on parse failure.
     * Pure — unit-tested.
     */
    fun withIncludeThoughts(requestBody: String): String? {
        val root = runCatching { JSONObject(requestBody) }.getOrNull() ?: return null
        val extraBody = root.optJSONObject("extra_body") ?: JSONObject()
        val google = extraBody.optJSONObject("google") ?: JSONObject()
        val thinking = google.optJSONObject("thinking_config") ?: JSONObject()

        // The enabling field is generation-specific — 3.x wants thinking_level, 2.5-era wants a
        // positive thinking_budget. Sending thinking_level to a 2.5 model 400s (verified live), which
        // previously broke EVERY traced Gemini-2.5 run.
        val useLevel = GeminiThinkingWire.usesThinkingLevel(root.optString("model").takeIf { it.isNotBlank() })
        val alreadyThinking = if (useLevel) thinking.optString("thinking_level") == "low" else thinking.has("thinking_budget")

        // Idempotent: both the enabling field and include_thoughts already present → nothing to change.
        if (thinking.optBoolean("include_thoughts", false) && alreadyThinking) return null

        if (useLevel) {
            thinking.put("thinking_level", "low") // cheapest level that still yields thought summaries
        } else if (!thinking.has("thinking_budget")) {
            thinking.put("thinking_budget", GeminiThinkingWire.budgetFor(ReasoningLevel.LOW))
        }
        thinking.put("include_thoughts", true)
        google.put("thinking_config", thinking)
        extraBody.put("google", google)
        root.put("extra_body", extraBody)
        return root.toString()
    }
}
