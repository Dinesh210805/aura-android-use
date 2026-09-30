package com.aura.aura_ui.agent.llm

import android.util.Log
import com.aura.aura_ui.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/**
 * Wire-level workaround for **Koog 1.0.0 issues #2095 / #2096** — double
 * JSON-encoding of tool-call arguments.
 *
 * Koog's OpenAI-compatible client (`AbstractOpenAILLMClient`) serializes an
 * assistant message's `function.arguments` with
 * `Json.encodeToString(String.serializer(), rawArgs)`, wrapping already-valid
 * JSON in an extra string layer: the model's `{}` goes out as `"{}"`. Strict
 * OpenAI-compatible backends (Groq, DashScope/Qwen) reject this with HTTP 400
 * `cannot unmarshal string into Go value of type map[string]interface {}`.
 *
 * The fix exists on Koog's `develop` but no release newer than 1.0.0 ships it,
 * so we repair the exact defect on the wire. The repair is the precise inverse
 * of the bug — `Json.decodeFromString<String>(arguments)` unwraps the one extra
 * layer Koog added — and is **self-disabling**: once arguments are correctly
 * encoded (a JSON object, not a JSON string) the decode throws and the value is
 * left untouched, so this stays correct after a future Koog upgrade.
 *
 * Always-on (it is a correctness fix, not diagnostics) and reused by every
 * provider that routes through Koog's OpenAI-base client — Groq now, OpenRouter
 * in Phase 2. Gemini's `GoogleLLMClient` uses a different serializer and is not
 * touched by this.
 *
 * REMOVE when Koog > 1.0.0 (with the #2095/#2096 fix) is adopted.
 */
internal object KoogOpenAiToolArgRepair {

    private const val TAG = "AuraAgentHttp"
    private val json = Json { ignoreUnknownKeys = true }

    /** An OkHttp interceptor that repairs double-encoded tool-call arguments. Fails open. */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val repaired = runCatching { repair(request) }.getOrNull()
        chain.proceed(repaired ?: request)
    }

    /** Returns a repaired request, or null when nothing needs changing / on any parse failure. */
    private fun repair(request: Request): Request? {
        val body = request.body ?: return null
        val mediaType = body.contentType()
        if (mediaType?.subtype?.contains("json", ignoreCase = true) != true) return null

        val original = Buffer().also { body.writeTo(it) }.readUtf8()
        val root = json.parseToJsonElement(original) as? JsonObject ?: return null
        val messages = root["messages"] as? JsonArray ?: return null

        var changed = false
        val newMessages = JsonArray(
            messages.map { message ->
                val msg = message as? JsonObject ?: return@map message
                val calls = msg["tool_calls"] as? JsonArray ?: return@map message
                val newCalls = JsonArray(
                    calls.map { call ->
                        val c = call as? JsonObject ?: return@map call
                        val fn = c["function"] as? JsonObject ?: return@map call
                        val args = fn["arguments"] as? JsonPrimitive ?: return@map call
                        if (!args.isString) return@map call
                        // Inverse of Koog's bug: unwrap exactly one String layer.
                        // Throws (→ null) when args are already a JSON object → leave as-is.
                        val unwrapped = runCatching { json.decodeFromString<String>(args.content) }
                            .getOrNull() ?: return@map call
                        changed = true
                        JsonObject(c + ("function" to JsonObject(fn + ("arguments" to JsonPrimitive(unwrapped)))))
                    },
                )
                JsonObject(msg + ("tool_calls" to newCalls))
            },
        )

        if (!changed) return null
        if (BuildConfig.DEBUG) Log.i(TAG, "Repaired Koog double-encoded tool-call arguments (#2095/#2096)")
        val newBody = json.encodeToString(JsonObject(root + ("messages" to newMessages))).toRequestBody(mediaType)
        return request.newBuilder().method(request.method, newBody).build()
    }
}
