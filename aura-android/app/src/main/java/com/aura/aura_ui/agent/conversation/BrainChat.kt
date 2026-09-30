package com.aura.aura_ui.agent.conversation

import android.content.Context
import android.util.Log
import com.aura.aura_ui.agent.llm.LlmEndpoint
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.mcp.bridge.CustomEndpointStore
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The user's configured agent brain: which endpoint, which key, which model. */
data class Brain(val endpoint: LlmEndpoint, val apiKey: String, val modelId: String)

/**
 * Resolves and calls the AGENT'S OWN brain over plain OpenAI-compatible `/chat/completions` —
 * no Koog, no MCP, no tools, no ledger, no wake-lock.
 *
 * Extracted from [AgentBrainSummarizer], which pioneered this shape, so the conversation plane's
 * lightweight brain calls share one implementation of endpoint resolution and auth. Two copies
 * would drift the moment a new [AuthScheme] appears, and the failure would be a silent 401 in
 * whichever copy was forgotten.
 *
 * Every failure path returns null rather than throwing: these calls sit on a live voice session,
 * where an exception costs the user a turn.
 */
object BrainChat {

    private const val TAG = "BrainChat"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Mirrors `AuraAgent.savedCredentials` — selected endpoint (built-in or custom), key, model. */
    fun resolve(context: Context): Brain? = runCatching {
        val store = ProviderKeyStore(context)
        val id = store.getSelectedEndpointId()
        val endpoint = LlmEndpointCatalog.builtinById(id)
            ?: CustomEndpointStore(EncryptedJsonStore(context, CustomEndpointStore.STORE_NAME))
                .allEndpoints().firstOrNull { it.id == id }
            ?: return null
        val apiKey = if (endpoint.requiresKey) store.getKey(id) ?: return null else ""
        val modelId = store.getSelectedModel(id) ?: return null
        Brain(endpoint, apiKey, modelId)
    }.getOrNull()

    /** One chat/completions turn. Returns the assistant text, or null on any failure. */
    suspend fun complete(
        brain: Brain,
        prompt: String,
        temperature: Double,
        httpClient: OkHttpClient,
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", brain.modelId)
            put(
                "messages",
                JSONArray().put(JSONObject().apply { put("role", "user"); put("content", prompt) }),
            )
            put("temperature", temperature)
            // Gemini thinks at its model default when no level is sent, which on 3.x can outlast
            // the lane's 15 s timeout. The agent lane already pins "low" (GeminiThinkingConfig).
            if (brain.modelId.lowercase().contains("gemini")) {
                val thinking = if (com.aura.aura_ui.agent.llm.GeminiThinkingWire.usesThinkingLevel(brain.modelId)) {
                    JSONObject().put("thinking_level", "low")
                } else {
                    JSONObject().put("thinking_budget", 0)
                }
                put("extra_body", JSONObject().put("google", JSONObject().put("thinking_config", thinking)))
            }
        }.toString()
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "brain call → ${brain.endpoint.id}/${brain.modelId}, prompt ${prompt.length} chars")
        runCatching {
            val builder = Request.Builder()
                .url(brain.endpoint.baseUrl.trimEnd('/') + brain.endpoint.chatCompletionsPath)
                .post(body.toRequestBody(JSON_MEDIA))
            // Bearer for every endpoint, exactly as [LlmEndpoint.authScheme] says: that field
            // describes the /models fetch, and "the chat leg is always Bearer". Gemini is the
            // one provider where the two differ — its native /v1beta/models requires
            // `x-goog-api-key`, while the OpenAI-compatibility path this call uses accepts only
            // `Authorization: Bearer` and answers anything else with
            // `400 Missing or invalid Authorization header`. Switching on authScheme here made
            // every Gemini brain call fail while the model list kept working, which is how the
            // voice assistant ended up telling users it could not reach its own brain.
            //
            // No key means a keyless local endpoint (Ollama): an empty Bearer is a 401 we invent.
            if (brain.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${brain.apiKey}")
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // The provider's own explanation, not just the number. A bare "HTTP 400"
                    // is unactionable — it cannot distinguish a dead model id from a rejected
                    // parameter, and both look identical to the user as AURA saying it can't
                    // reach its brain. Truncated because provider errors can echo the prompt.
                    val why = runCatching { resp.body?.string().orEmpty() }.getOrDefault("").take(400)
                    Log.w(TAG, "brain HTTP ${resp.code} (${brain.endpoint.id}/${brain.modelId}): $why")
                    return@use null
                }
                Log.i(TAG, "brain HTTP ${resp.code} in ${System.currentTimeMillis() - startedAt} ms")
                JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
            }
        }.getOrElse {
            Log.w(TAG, "brain call failed after ${System.currentTimeMillis() - startedAt} ms: $it")
            null
        }
    }

    /**
     * Default client for brain calls.
     *
     * The read timeout matters more here than anywhere else in the app: this call sits between
     * the user speaking and AURA replying, so an unreachable provider is heard as AURA going
     * silent. Short enough that a dead endpoint degrades to a spoken apology rather than a hang.
     */
    fun defaultClient(readTimeoutSeconds: Long = 30): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()
}
