package com.aura.aura_ui.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * Adds the `index` Gemini leaves out of each streamed tool call, so Koog can read the stream.
 *
 * - Why: Gemini's OpenAI-compatible endpoint streams `choices[].delta.tool_calls[]` items
 *   without `index`. Koog's `OpenAIStreamToolCall` requires it, so the stream failed only after
 *   the whole reply had arrived, and every step was asked a second time without streaming.
 * - Contract: rewrites the `text/event-stream` body of a Gemini-host response line by line as it
 *   arrives, so streaming still shows words as they come. Gemini sends each tool call whole in
 *   one item with its own `id`, so items get 0, 1, 2… in the order they appear across the
 *   stream. An item that has an `index` is left alone. Every other line passes through.
 * - Fails: open. A line that does not parse is passed on unchanged.
 * - Change together: [OpenAiCompatProvider] (wired with [GeminiFinishReasonRepair], same gating).
 */
internal object GeminiStreamToolIndexRepair {

    private const val GEMINI_HOST = "generativelanguage.googleapis.com"

    private val json = Json { ignoreUnknownKeys = true }

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (request.url.host != GEMINI_HOST || body == null || body.contentType()?.subtype != "event-stream") {
            return@Interceptor response
        }
        val next = Counter()
        response.newBuilder().body(Rewritten(body, body.contentType()) { withToolIndex(it, next) }).build()
    }

    /** The next tool-call index within one streamed response. */
    class Counter {
        var value = 0
    }

    /**
     * [line] with an `index` added to each tool-call item that lacks one, numbered from [next].
     * Returns [line] unchanged when it is not a `data:` line with such items, or does not parse. Pure
     * apart from advancing [next].
     */
    fun withToolIndex(line: String, next: Counter): String {
        if (!line.startsWith("data:") || "\"tool_calls\"" !in line) return line
        val root = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()) as? JsonObject }.getOrNull() ?: return line
        val choices = root["choices"] as? JsonArray ?: return line
        var changed = false
        val fixed = JsonArray(
            choices.map { choice ->
                val c = choice as? JsonObject ?: return@map choice
                val delta = c["delta"] as? JsonObject ?: return@map choice
                val calls = delta["tool_calls"] as? JsonArray ?: return@map choice
                val numbered = JsonArray(
                    calls.map { call ->
                        val o = call as? JsonObject
                        if (o == null || "index" in o) return@map call
                        changed = true
                        JsonObject(o + ("index" to JsonPrimitive(next.value++)))
                    },
                )
                JsonObject(c + ("delta" to JsonObject(delta + ("tool_calls" to numbered))))
            },
        )
        if (!changed) return line
        return "data: " + json.encodeToString(JsonObject(root + ("choices" to fixed)))
    }

    /** [original]'s body with each line passed through [fix] as it is read. */
    private class Rewritten(
        private val original: ResponseBody,
        private val type: MediaType?,
        private val fix: (String) -> String,
    ) : ResponseBody() {
        private val lines by lazy { LineSource(original.source(), fix).buffer() }

        override fun contentType(): MediaType? = type

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = lines
    }

    private class LineSource(private val upstream: BufferedSource, private val fix: (String) -> String) : Source {
        private val pending = Buffer()

        override fun read(sink: Buffer, byteCount: Long): Long {
            while (pending.size == 0L) {
                val line = upstream.readUtf8Line() ?: return -1
                pending.writeUtf8(fix(line)).writeUtf8("\n")
            }
            return pending.read(sink, minOf(byteCount, pending.size))
        }

        override fun timeout(): Timeout = upstream.timeout()

        override fun close() = upstream.close()
    }
}
