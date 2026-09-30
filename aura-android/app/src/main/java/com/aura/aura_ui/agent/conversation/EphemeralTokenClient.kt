package com.aura.aura_ui.agent.conversation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mints a single-use ephemeral auth token from the user's BYOK key so the long-lived key never enters
 * the Live WebSocket URL or logs (Google's recommended client-side pattern). `liveConnectConstraints`
 * locks the token to one model. Best-effort: any failure returns null and the caller connects with the
 * raw key (the key is already on the user's own device, so this is a defense-in-depth improvement).
 */
class EphemeralTokenClient(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun mint(apiKey: String, model: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("uses", 1)
                putJsonObject("liveConnectConstraints") { put("model", model) }
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1alpha/auth_tokens?key=$apiKey")
                .post(payload).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(resp.body?.string().orEmpty())
                    .jsonObject["name"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrElse { Log.w(TAG, "ephemeral token mint failed: ${it.message}"); null }
    }

    private companion object { const val TAG = "EphemeralToken" }
}
