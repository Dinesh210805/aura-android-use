package com.aura.aura_ui.agent.llm

import android.util.Log
import com.aura.aura_ui.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/**
 * Wire-level echo for **OpenRouter's message-level `reasoning_details`**.
 *
 * Reasoning models served via OpenRouter (Gemini 3, Claude, …) pause their reasoning when
 * they emit a tool call and attach the interim reasoning as a `reasoning_details` array on
 * `choices[].message`. OpenRouter requires that array passed back **unmodified** on the
 * matching assistant message of the next request — the block sequence may not be rearranged
 * or edited (openrouter.ai/docs/guides/best-practices/reasoning-tokens). Koog's
 * [ai.koog.prompt.executor.clients.openai.OpenAILLMClient] has no model field for it, so it
 * is silently dropped on the round-trip and multi-step tool turns fail — for Gemini 3 with
 * the same 400 the direct-Gemini path fixed via [GeminiThoughtSignatureRelay].
 *
 * Same repair pattern as the relay, one level up: the reasoning rides on the MESSAGE, not
 * the tool call, so entries are keyed by the message's FIRST tool-call id (unique, and
 * round-tripped by the OpenAI wire via `tool_call_id`). Messages without tool calls are
 * skipped — no stable key, and no continuation needs them.
 *
 * OpenRouter-only by profile ([ProviderProfile.reasoningResponseStyle] gates the add), with
 * the host check kept as belt-and-braces. Both transforms **fail open**. State is a small
 * bounded LRU shared across the two HTTP calls of a turn, exactly like the relay.
 */
internal object OpenRouterReasoningDetailsEcho {

    private const val TAG = "AuraAgentHttp"
    private const val OPENROUTER_HOST = "openrouter.ai"
    private const val MAX_ENTRIES = 128

    private val json = Json { ignoreUnknownKeys = true }

    /** first toolCallId of the message → its `reasoning_details` array. Bounded LRU. */
    private val details = object : LinkedHashMap<String, JsonElement>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonElement>) =
            size > MAX_ENTRIES
    }

    /**
     * Single interceptor for both legs: inject captured details into the outgoing request,
     * then capture fresh ones off the response. Only runs for the OpenRouter host.
     */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host != OPENROUTER_HOST) return@Interceptor chain.proceed(request)

        val outgoing = runCatching {
            val original = request.body ?: return@runCatching request
            val mediaType = original.contentType()
            if (mediaType?.subtype?.contains("json", ignoreCase = true) != true) return@runCatching request
            val text = Buffer().also { original.writeTo(it) }.readUtf8()
            val patched = injectIntoRequest(text) ?: return@runCatching request
            request.newBuilder().method(request.method, patched.toRequestBody(mediaType)).build()
        }.getOrDefault(request)

        val response = chain.proceed(outgoing)
        runCatching {
            val body = response.peekBody(Long.MAX_VALUE).string()
            captureFromResponse(body)
        }
        response
    }

    /** Records every tool-calling message's reasoning_details. Fails open (no throw). */
    fun captureFromResponse(responseBody: String) {
        val found = extractReasoningDetails(responseBody)
        if (found.isEmpty()) return
        synchronized(details) { found.forEach { (id, d) -> details[id] = d } }
        if (BuildConfig.DEBUG) Log.i(TAG, "Captured reasoning_details for ${found.size} message(s)")
    }

    /** Re-injects stored details into [requestBody]; null when nothing changes. */
    fun injectIntoRequest(requestBody: String): String? {
        val snapshot = synchronized(details) { if (details.isEmpty()) return null else HashMap(details) }
        return injectReasoningDetails(requestBody, snapshot)?.also {
            if (BuildConfig.DEBUG) Log.i(TAG, "Re-injected reasoning_details")
        }
    }

    // ── Pure transforms (unit-tested) ──────────────────────────────────────────

    /**
     * Maps `firstToolCallId → reasoning_details` for every response message that carries
     * BOTH a non-empty `tool_calls` and a non-empty `reasoning_details`. Reads OpenAI's
     * non-streaming shape (`choices[].message`). Fails open to an empty map.
     */
    fun extractReasoningDetails(responseBody: String): Map<String, JsonElement> {
        val root = runCatching { json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull() ?: return emptyMap()
        val choices = root["choices"] as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, JsonElement>()
        choices.forEach { choice ->
            val message = (choice as? JsonObject)?.get("message") as? JsonObject ?: return@forEach
            val reasoning = message["reasoning_details"] as? JsonArray ?: return@forEach
            if (reasoning.isEmpty()) return@forEach
            val calls = message["tool_calls"] as? JsonArray ?: return@forEach
            val first = calls.firstOrNull() as? JsonObject ?: return@forEach
            val id = (first["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            out[id] = reasoning
        }
        return out
    }

    /**
     * Adds the stored `reasoning_details` to each assistant tool-calling message in
     * [requestBody] whose FIRST tool-call `id` is in [stored] and that does not already
     * carry the field. Returns the rewritten body, or null when nothing matched.
     */
    fun injectReasoningDetails(requestBody: String, stored: Map<String, JsonElement>): String? {
        if (stored.isEmpty()) return null
        if ("tool_calls" !in requestBody) return null
        val root = runCatching { json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull() ?: return null
        val messages = root["messages"] as? JsonArray ?: return null

        var changed = false
        val newMessages = JsonArray(
            messages.map { message ->
                val msg = message as? JsonObject ?: return@map message
                if (msg["reasoning_details"] != null) return@map message
                val calls = msg["tool_calls"] as? JsonArray ?: return@map message
                val first = calls.firstOrNull() as? JsonObject ?: return@map message
                val id = (first["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@map message
                val stashed = stored[id] ?: return@map message
                changed = true
                JsonObject(msg + ("reasoning_details" to stashed))
            },
        )

        if (!changed) return null
        return json.encodeToString(JsonObject(root + ("messages" to newMessages)))
    }
}
