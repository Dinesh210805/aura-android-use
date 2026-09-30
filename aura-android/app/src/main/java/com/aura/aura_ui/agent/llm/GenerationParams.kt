package com.aura.aura_ui.agent.llm

import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject

/**
 * Splices the user's [GenerationConfig] (thinking level + temperature) onto the outgoing
 * OpenAI-compat request, mapping the reasoning level to the endpoint's own wire dialect keyed on
 * its [ReasoningRequestStyle] (D2 piece 2). Verified wire facts (2026-07-13):
 *
 *  - [ReasoningRequestStyle.OPENAI_REASONING_EFFORT] → top-level `reasoning_effort: "low|medium|high"`.
 *  - [ReasoningRequestStyle.OPENROUTER_REASONING]   → `reasoning: {"effort": …}` / `{"enabled": false}`.
 *  - [ReasoningRequestStyle.GEMINI_THINKING_CONFIG] → `extra_body.google.thinking_config`.
 *  - temperature → top-level `"temperature"` on any wire (replace-or-insert; Koog may already emit one).
 *
 * Pure + fail-open, mirroring [GeminiThinkingConfig]. A [GenerationConfig.isDefault] config changes
 * nothing (returns null), so an untouched setup sends a byte-identical request to pre-piece-2.
 */
object GenerationParams {

    fun interceptor(style: ReasoningRequestStyle, config: GenerationConfig): Interceptor =
        Interceptor { chain ->
            val request = chain.request()
            val patched = runCatching {
                val body = request.body ?: return@runCatching request
                val mediaType = body.contentType()
                if (mediaType?.subtype?.contains("json", ignoreCase = true) != true) return@runCatching request
                val text = Buffer().also { body.writeTo(it) }.readUtf8()
                val newText = apply(text, style, config) ?: return@runCatching request
                request.newBuilder().method(request.method, newText.toRequestBody(mediaType)).build()
            }.getOrDefault(request)
            chain.proceed(patched)
        }

    /**
     * Rewrite [requestBody] with the config's temperature + reasoning. Returns the new body, or
     * null when nothing changed (default config, an OFF that the style can't express, or a parse
     * failure). Pure — unit-tested.
     */
    fun apply(requestBody: String, style: ReasoningRequestStyle, config: GenerationConfig): String? {
        if (config.isDefault) return null
        val root = runCatching { JSONObject(requestBody) }.getOrNull() ?: return null
        var changed = false

        config.temperature?.let {
            // Replace-or-insert: put() overwrites any temperature Koog already serialized, so the
            // request never carries a duplicate key.
            root.put("temperature", it)
            changed = true
        }

        if (applyReasoning(root, style, config.reasoning)) changed = true

        return if (changed) root.toString() else null
    }

    /** Map the reasoning level to [style]'s wire field. Returns true if it wrote anything. */
    private fun applyReasoning(root: JSONObject, style: ReasoningRequestStyle, level: ReasoningLevel): Boolean {
        if (level == ReasoningLevel.DEFAULT) return false
        return when (style) {
            ReasoningRequestStyle.OPENAI_REASONING_EFFORT -> {
                // The OpenAI chat API has no "off" — OFF falls through to the provider default.
                val effort = level.effortOrNull() ?: return false
                root.put("reasoning_effort", effort)
                true
            }
            ReasoningRequestStyle.OPENROUTER_REASONING -> {
                val reasoning = root.optJSONObject("reasoning") ?: JSONObject()
                if (level == ReasoningLevel.OFF) {
                    reasoning.put("enabled", false)
                } else {
                    reasoning.put("effort", level.effortOrNull())
                }
                root.put("reasoning", reasoning)
                true
            }
            ReasoningRequestStyle.GEMINI_THINKING_CONFIG -> {
                val extraBody = root.optJSONObject("extra_body") ?: JSONObject()
                val google = extraBody.optJSONObject("google") ?: JSONObject()
                val thinking = google.optJSONObject("thinking_config") ?: JSONObject()
                // The field is generation-specific: Gemini 3.x speaks thinking_level, 2.5-era speaks an
                // integer thinking_budget. Sending the wrong one 400s (verified live). The model id is
                // right here in the request body.
                if (GeminiThinkingWire.usesThinkingLevel(root.optString("model").takeIf { it.isNotBlank() })) {
                    // Gemini 3.x: "low"/"high" (MEDIUM maps down to "low"). OFF is gated out for gen 3.
                    thinking.put("thinking_level", if (level == ReasoningLevel.HIGH) "high" else "low")
                } else {
                    // Gemini 2.5-era: integer budget; 0 disables (OFF), a band otherwise.
                    thinking.put("thinking_budget", GeminiThinkingWire.budgetFor(level))
                }
                google.put("thinking_config", thinking)
                extraBody.put("google", google)
                root.put("extra_body", extraBody)
                true
            }
            ReasoningRequestStyle.NONE -> false
        }
    }

    /** OpenAI-style effort keyword, or null for OFF/DEFAULT (no keyword to send). */
    private fun ReasoningLevel.effortOrNull(): String? = when (this) {
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.OFF, ReasoningLevel.DEFAULT -> null
    }
}
