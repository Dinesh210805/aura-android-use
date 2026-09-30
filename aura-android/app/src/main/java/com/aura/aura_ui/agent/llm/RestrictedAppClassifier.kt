package com.aura.aura_ui.agent.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import android.content.Context
import android.util.Log
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.data.AppInfo
import com.aura.aura_ui.mcp.bridge.CustomEndpointStore
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.mcp.bridge.RestrictedAppEntry

/**
 * One-shot classification of the device's installed apps into AURA's
 * restricted-app categories (banking, payment, trading/crypto, auth/password).
 *
 * Provider-agnostic by construction: reuses whichever endpoint/key/model the
 * user already configured in Settings → Agent Brain (same resolution
 * `AuraAgent.savedCredentials()` uses internally), routed through the same
 * [ProviderRegistry] every other on-device LLM call goes through — Groq, Gemini,
 * OpenRouter, or a custom OpenAI-compatible endpoint all work identically here,
 * with no per-provider branching in this class.
 *
 * A single request per chunk, no tools, no MCP server attached — this is
 * classification, not agentic action.
 */
class RestrictedAppClassifier(private val context: Context) {

    /**
     * Classify [apps] (caller decides the scope — typically non-system apps
     * from [com.aura.aura_ui.utils.AppInventoryScanner]). Returns suggestions
     * ONLY for apps the model flagged as sensitive; apps it judged unremarkable
     * are simply absent from the result.
     *
     * Fails loudly if no provider is configured — same posture as
     * `AuraAgent.runFromSavedSettings()` — so the caller (Settings screen)
     * surfaces "set up Agent Brain first" instead of a silent empty result.
     */
    suspend fun classify(apps: List<AppInfo>): List<RestrictedAppEntry> {
        if (apps.isEmpty()) return emptyList()
        val saved = savedCredentials()
        val client = ProviderRegistry.clientFor(saved.endpoint, saved.apiKey, saved.modelId)
        val model = ProviderRegistry.modelFor(saved.endpoint, saved.modelId)
        val nowMs = System.currentTimeMillis()

        // Chunk so a large device inventory never blows the model's context — a
        // few hundred short "package | label" lines per call is comfortably
        // small, and results merge cleanly regardless of chunk boundaries.
        val suggestions = mutableListOf<RestrictedAppEntry>()
        for (chunk in apps.chunked(CHUNK_SIZE)) {
            val response = runCatching {
                val result = client.execute(buildPrompt(chunk), model)
                (result as? Message.Assistant)?.textContent() ?: result.toString()
            }.getOrElse { t ->
                Log.w(TAG, "Classification chunk failed (${chunk.size} apps): ${t.message}")
                continue
            }
            suggestions += RestrictedAppResponseParser.parse(response, chunk, nowMs)
        }
        return suggestions
    }

    private fun buildPrompt(chunk: List<AppInfo>) = prompt("aura-restricted-app-classifier") {
        system(
            "You classify Android apps by package name and label for a security " +
                "feature. Flag ONLY apps that are: banking apps, payment/UPI/wallet " +
                "apps, trading or cryptocurrency apps, or authenticator/password-manager " +
                "apps. Everything else (games, social, shopping, utilities, media, " +
                "system apps) must be OMITTED from your answer entirely — do not list " +
                "them with a NONE category, just leave them out.\n\n" +
                "Respond with ONLY a JSON array, no prose, no markdown code fence. Each " +
                "element: {\"package\":\"<exact package name from the input>\"," +
                "\"category\":\"BANKING|PAYMENT|TRADING_CRYPTO|AUTH_PASSWORD\"," +
                "\"confidence\":0.0-1.0}. If nothing qualifies, respond with [].",
        )
        user {
            text(chunk.joinToString("\n") { "${it.packageName} | ${it.appName}" })
        }
    }

    private data class SavedRunConfig(val endpoint: LlmEndpoint, val apiKey: String, val modelId: String)

    /**
     * Same resolution `AuraAgent.savedCredentials()` uses — duplicated (a dozen
     * lines) rather than shared, since classification is a distinct concern from
     * running an agent turn and doesn't need generation-config plumbing.
     */
    private fun savedCredentials(): SavedRunConfig {
        val store = ProviderKeyStore(context)
        val id = store.getSelectedEndpointId()
        val endpoint = LlmEndpointCatalog.builtinById(id)
            ?: CustomEndpointStore(EncryptedJsonStore(context, CustomEndpointStore.STORE_NAME))
                .allEndpoints().firstOrNull { it.id == id }
            ?: LlmEndpointCatalog.BUILTINS.first()
        val apiKey = if (endpoint.requiresKey) {
            store.getKey(id)
                ?: error("No API key configured for ${endpoint.displayName} — open Settings → Brain.")
        } else {
            ""
        }
        val modelId = store.getSelectedModel(id)
            ?: error("No model selected for ${endpoint.displayName} — fetch and pick one in Settings → Brain.")
        return SavedRunConfig(endpoint, apiKey, modelId)
    }

    companion object {
        private const val TAG = "RestrictedAppClassifier"
        private const val CHUNK_SIZE = 150
    }
}
