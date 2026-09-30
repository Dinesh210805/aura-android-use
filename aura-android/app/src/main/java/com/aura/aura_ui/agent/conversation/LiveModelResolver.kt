package com.aura.aura_ui.agent.conversation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves which Live model the user's BYOK key can actually use, instead of hard-coding a preview id
 * that Google rotates monthly (e.g. gemini-2.0-flash-live-001 was shut down 2025-12-09). [resolve]
 * asks ListModels for the key's models, keeps those advertising `bidiGenerateContent`, and [pick]
 * ranks them — native-audio first (free-tier friendly, best quality), then any half-cascade live model.
 * On any failure the caller falls back to the configured constant.
 */
object LiveModelResolver {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pure ranker, in preference order:
     *
     *  1. **The configured preferred model** ([CompanionConfig.LIVE_MODEL], currently
     *     `gemini-3.1-flash-live-preview`) when the key actually exposes it. Chosen by the product
     *     owner, so it outranks the generic heuristics below — the previous version ignored the
     *     configured constant entirely whenever ListModels succeeded, which made the setting look
     *     like it did nothing.
     *  2. native-audio (best quality, free-tier friendly),
     *  3. any other half-cascade live model,
     *  4. only as a last resort a `thinking` variant — thinking-dialog models deliberate for
     *     seconds EVERY turn, which reads as "the assistant ignored me for 10 s" in conversation.
     *     ListModels order is arbitrary, so without the explicit demotion a thinking variant can
     *     win on list position alone.
     */
    fun pick(candidates: List<String>, preferred: String = CompanionConfig.LIVE_MODEL): String? {
        val live = candidates.filter { it.contains("live") || it.contains("native-audio") }
        if (live.isEmpty()) return null
        // Match on the bare id too: the constant carries a "models/" prefix, ListModels may not.
        val preferredId = preferred.substringAfterLast('/')
        live.firstOrNull { it.substringAfterLast('/') == preferredId }?.let { return it }
        val (thinking, fast) = live.partition { it.contains("thinking") }
        return fast.firstOrNull { it.contains("native-audio") }
            ?: fast.firstOrNull()
            ?: thinking.first()
    }

    /** Query the key's Live-capable models; null on any failure (caller uses its default). */
    suspend fun resolve(apiKey: String, client: OkHttpClient = OkHttpClient()): String? =
        pick(listLiveModels(apiKey, client))

    /** Every model the key exposes that advertises `bidiGenerateContent`, on the v1beta endpoint. */
    suspend fun listLiveModels(apiKey: String, client: OkHttpClient = OkHttpClient()): List<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=1000")
                    .get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<String>()
                    val models = json.parseToJsonElement(resp.body?.string().orEmpty())
                        .jsonObject["models"]?.jsonArray ?: return@use emptyList<String>()
                    models.mapNotNull { m ->
                        val o = m.jsonObject
                        val methods = o["supportedGenerationMethods"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                        o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { "bidiGenerateContent" in methods }
                    }
                }
            }.getOrElse { Log.w("LiveModelResolver", "listLiveModels failed: ${it.message}"); emptyList() }
        }
}
