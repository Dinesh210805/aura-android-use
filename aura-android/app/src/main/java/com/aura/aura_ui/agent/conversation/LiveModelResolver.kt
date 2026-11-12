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
 * Lists the Gemini models a key can use for a given method. AURA's voice is locked to Gemini 3.8
 * Live (`LiveWire.MODEL`); this is only for features that look a model up, like the voice preview.
 */
object LiveModelResolver {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every model the key exposes that advertises [method] (e.g. `generateContent`), on v1beta.
     * - Fails: empty list on any network/parse error, logged.
     */
    suspend fun listModels(apiKey: String, method: String, client: OkHttpClient = OkHttpClient()): List<String> =
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
                        o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { method in methods }
                    }
                }
            }.getOrElse { Log.w("LiveModelResolver", "listModels($method) failed: ${it.message}"); emptyList() }
        }
}
