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
 * Wire-level relay for **Gemini 3 thought signatures** over the OpenAI-compat surface.
 *
 * Gemini 3.x are *thinking* models. When they return a `functionCall`, they attach an
 * opaque per-call `thought_signature` and then **require** that signature to be echoed
 * back on the matching assistant tool-call in the next request. Omitting it fails the
 * whole turn with `400 INVALID_ARGUMENT`:
 *
 * > "Function call is missing a thought_signature in functionCall parts … function call
 * > `default_api:lookup_app`, position 2."
 *
 * On Gemini's `/v1beta/openai/chat/completions` surface the signature rides on the
 * tool-call as `tool_calls[].extra_content.google.thought_signature`. Koog's
 * [ai.koog.prompt.executor.clients.openai.OpenAILLMClient] has no model field for it,
 * so it is silently dropped on the round-trip — exactly the class of OpenAI-compat gap
 * the sibling [KoogOpenAiToolArgRepair] patches. We repair it the same way: capture the
 * `extra_content` off each response tool-call (keyed by its `id`) and re-inject it into
 * the matching assistant tool-call on the next request.
 *
 * Keying by tool-call `id` is exact: the OpenAI wire round-trips that id (the `tool`
 * result message references it via `tool_call_id`), and Gemini's ids are unique per call.
 *
 * Gemini-only by nature — Groq/OpenRouter never emit `extra_content`, so for them the
 * capture map stays empty and injection is a no-op. The [interceptor] still gates on the
 * Gemini host to skip parsing other providers' bodies. Both transforms **fail open**.
 *
 * The relay is stateful across the two HTTP calls of a turn via a small bounded map;
 * the OkHttp client is reused for every call within an agent run, so capture-then-replay
 * works without threading state through Koog.
 */
internal object GeminiThoughtSignatureRelay {

    private const val TAG = "AuraAgentHttp"
    private const val GEMINI_HOST = "generativelanguage.googleapis.com"
    private const val MAX_ENTRIES = 128

    private val json = Json { ignoreUnknownKeys = true }

    /** toolCallId → its `extra_content` object. Bounded LRU; access is synchronized. */
    private val signatures = object : LinkedHashMap<String, JsonElement>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonElement>) =
            size > MAX_ENTRIES
    }

    /**
     * Single interceptor that does both legs: inject captured signatures into the outgoing
     * request, then capture fresh ones off the response. Only runs for the Gemini host.
     */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host != GEMINI_HOST) return@Interceptor chain.proceed(request)

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

    /** Records every tool-call signature in [responseBody]. Fails open (no throw). */
    fun captureFromResponse(responseBody: String) {
        val sigs = extractToolCallSignatures(responseBody)
        if (sigs.isEmpty()) return
        synchronized(signatures) { sigs.forEach { (id, sig) -> signatures[id] = sig } }
        if (BuildConfig.DEBUG) Log.i(TAG, "Captured ${sigs.size} Gemini thought-signature(s)")
    }

    /** Re-injects stored signatures into [requestBody]; null when nothing changes. */
    fun injectIntoRequest(requestBody: String): String? {
        val snapshot = synchronized(signatures) { if (signatures.isEmpty()) return null else HashMap(signatures) }
        return injectToolCallSignatures(requestBody, snapshot)?.also {
            if (BuildConfig.DEBUG) Log.i(TAG, "Re-injected Gemini thought-signature(s)")
        }
    }

    // ── Pure transforms (unit-tested) ──────────────────────────────────────────

    /**
     * Maps `toolCallId → extra_content` for every response tool-call that carries one.
     * Reads OpenAI's non-streaming shape (`choices[].message.tool_calls[]`). Fails open
     * to an empty map on any parse failure.
     */
    fun extractToolCallSignatures(responseBody: String): Map<String, JsonElement> {
        val root = runCatching { json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull() ?: return emptyMap()
        val choices = root["choices"] as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, JsonElement>()
        choices.forEach { choice ->
            val message = (choice as? JsonObject)?.get("message") as? JsonObject ?: return@forEach
            val calls = message["tool_calls"] as? JsonArray ?: return@forEach
            calls.forEach { call ->
                val c = call as? JsonObject ?: return@forEach
                val id = (c["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                val extra = c["extra_content"] as? JsonObject ?: return@forEach
                out[id] = extra
            }
        }
        return out
    }

    /**
     * Adds the stored `extra_content` to each assistant tool-call in [requestBody] whose
     * `id` is in [sigs] and that does not already carry one. Returns the rewritten body,
     * or null when nothing matched (so the caller leaves the request untouched).
     */
    fun injectToolCallSignatures(requestBody: String, sigs: Map<String, JsonElement>): String? {
        if (sigs.isEmpty()) return null
        if ("tool_calls" !in requestBody) return null
        val root = runCatching { json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull() ?: return null
        val messages = root["messages"] as? JsonArray ?: return null

        var changed = false
        val newMessages = JsonArray(
            messages.map { message ->
                val msg = message as? JsonObject ?: return@map message
                val calls = msg["tool_calls"] as? JsonArray ?: return@map message
                val newCalls = JsonArray(
                    calls.map { call ->
                        val c = call as? JsonObject ?: return@map call
                        if (c["extra_content"] != null) return@map call
                        val id = (c["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@map call
                        val sig = sigs[id] ?: return@map call
                        changed = true
                        JsonObject(c + ("extra_content" to sig))
                    },
                )
                JsonObject(msg + ("tool_calls" to newCalls))
            },
        )

        if (!changed) return null
        return json.encodeToString(JsonObject(root + ("messages" to newMessages)))
    }
}
