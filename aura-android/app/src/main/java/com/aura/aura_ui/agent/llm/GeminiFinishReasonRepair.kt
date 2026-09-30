package com.aura.aura_ui.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Crash fix for Gemini 3.x **thought-only responses** on the OpenAI-compat surface.
 *
 * When the model burns its whole turn on thinking (no content, no tool call), Gemini
 * returns `choices[0]` with **no `finish_reason` field**. Koog's
 * [ai.koog.prompt.executor.clients.openai.OpenAILLMClient] declares `finishReason`
 * required, so kotlinx.serialization throws and the entire agent run dies:
 *
 * > "Field 'finishReason' is required for type … OpenAIChoice, but it was missing
 * >  at path: $.choices[0]"
 *
 * (Observed on-device: runs 1783008241421-9b007b4e / 1783007902441-434f5e46 — both
 * Amazon add-to-cart runs crashed on the final call, `completion_tokens=0`, body a
 * single `<thought>…</thought>` block.)
 *
 * The repair injects `finish_reason: "stop"` into any choice missing it, so the turn
 * deserializes and the loop continues (the thought text itself is stripped from any
 * user-facing reply by [ReplySanitizer]). Same OpenAI-compat-gap family as
 * [KoogOpenAiToolArgRepair] / [GeminiThoughtSignatureRelay]; Gemini-host gated;
 * fails open. Added to [OpenAiCompatProvider]'s chain BEFORE the trace tap (which
 * sits closer to the network), so the tap records the raw wire truth
 * (`finish=None` stays visible in the trace) while Koog sees the repaired body.
 */
internal object GeminiFinishReasonRepair {

    private const val GEMINI_HOST = "generativelanguage.googleapis.com"

    private val json = Json { ignoreUnknownKeys = true }

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.url.host != GEMINI_HOST) return@Interceptor response
        runCatching {
            val body = response.body ?: return@runCatching response
            val mediaType = body.contentType()
            if (mediaType?.subtype?.contains("json", ignoreCase = true) != true) return@runCatching response
            if (!response.isSuccessful) return@runCatching response
            // Consume (and thereby close) the original body, then rebuild.
            val text = body.string()
            val repaired = withFinishReason(text)
            response.newBuilder().body((repaired ?: text).toResponseBody(mediaType)).build()
        }.getOrDefault(response)
    }

    /**
     * Injects `finish_reason: "stop"` into every choice where the field is missing or
     * JSON null. Returns the rewritten body, or null when nothing needed repair or on
     * parse failure. Pure — unit-tested.
     */
    fun withFinishReason(responseBody: String): String? {
        if ("\"choices\"" !in responseBody) return null
        val root = runCatching { json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull() ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        var changed = false
        val newChoices = JsonArray(
            choices.map { choice ->
                val c = choice as? JsonObject ?: return@map choice
                val finishReason = c["finish_reason"]
                if (finishReason != null && finishReason != JsonNull) return@map choice
                changed = true
                JsonObject(c + ("finish_reason" to JsonPrimitive("stop")))
            },
        )
        if (!changed) return null
        return json.encodeToString(JsonObject(root + ("choices" to newChoices)))
    }
}
