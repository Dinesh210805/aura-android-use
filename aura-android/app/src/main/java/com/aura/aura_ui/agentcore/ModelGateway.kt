package com.aura.aura_ui.agentcore

import android.content.Context
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import com.aura.aura_ui.agent.llm.GenerationConfig
import com.aura.aura_ui.agent.llm.LlmEndpoint
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.llm.ProviderRegistry
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import com.aura.aura_ui.mcp.bridge.CustomEndpointStore
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore

/**
 * Turns a [ModelRole] into a model that is ready to call.
 *
 * - Contract: [ModelProvider.resolve] returns null when the user has not finished setup for that
 *   role. It never falls back to another provider's model id, because a wrong id fails every call.
 * - Fails: a provider error surfaces from the call, after the client's own retries (which honour
 *   the server's retry-after). This file adds no pacing: guessing a rate limit the app cannot
 *   see is wrong for either the free tier or the paid one.
 * - Reads/Writes: the user's saved endpoint, key, model and generation settings in
 *   `ProviderKeyStore` and `CustomEndpointStore` (both encrypted prefs). Read-only.
 * - Why the imports from `agent/llm`: the endpoint catalog and the per-provider wire fixes behind
 *   `ProviderRegistry.clientFor` are provider knowledge, not loop logic, and are reused as they
 *   are. Move them into this package when `agent/` is deleted.
 */
class ResolvedModel(
    val model: LLModel,
    val client: LLMClient,
    /** "Gemini / gemini-3.5-flash-lite", for the UI and for traces. */
    val label: String,
    /** The model's input limit in tokens, or null when the provider does not publish one. */
    val contextWindow: Int?,
    val vision: Boolean,
    val params: LLMParams,
) {
    fun executor(): PromptExecutor = MultiLLMPromptExecutor(model.provider to client)

    /**
     * One request with no tools and no history: summaries, titles, classification.
     * - Fails: returns null on any provider error. A side job must never fail the turn it serves.
     */
    suspend fun ask(instruction: String, input: String): String? = runCatching {
        val reply = client.execute(
            prompt("aura-aux") {
                system(instruction)
                user { text(input) }
            },
            model,
        )
        (reply as? Message.Assistant)?.textContent()?.takeIf { it.isNotBlank() }
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
}

interface ModelProvider {
    fun resolve(role: ModelRole): ResolvedModel?
}

/**
 * Resolves models from what the user saved in Settings.
 *
 * - Contract: [ModelRole.FAST] resolves to the main model until the user picks a cheaper one;
 *   [ModelRole.LOCAL] resolves to null until an on-device runtime is wired in ([LocalModel] covers
 *   the background jobs that must work without one).
 */
class SavedSettingsModelProvider(context: Context) : ModelProvider {
    private val app = context.applicationContext

    override fun resolve(role: ModelRole): ResolvedModel? = when (role) {
        ModelRole.MAIN, ModelRole.FAST -> runCatching { main() }.getOrNull()
        ModelRole.LOCAL -> null
    }

    private fun main(): ResolvedModel? {
        val keys = ProviderKeyStore(app)
        val endpointId = keys.getSelectedEndpointId()
        val endpoint = endpointFor(endpointId) ?: return null
        val apiKey = if (endpoint.requiresKey) keys.getKey(endpointId) ?: return null else ""
        val modelId = keys.getSelectedModel(endpointId) ?: endpoint.defaultModelId.ifBlank { return null }
        val generation: GenerationConfig = keys.getGeneration(endpointId)
        val vision = keys.getSelectedModelVisionCapable(endpointId)
        return ResolvedModel(
            model = ProviderRegistry.modelFor(endpoint, modelId, vision),
            // Temperature and thinking level travel inside the client, mapped to each provider's wire.
            client = ProviderRegistry.clientFor(endpoint, apiKey, modelId, generation),
            label = "${endpoint.displayName} / $modelId",
            contextWindow = keys.getSelectedModelContextWindow(endpointId),
            // Unknown means "send images": withholding them from a model that can see is the worse error.
            vision = vision != false,
            // No output cap by default: on a thinking model the cap also counts reasoning tokens,
            // and a reply cut mid tool call is unusable.
            params = LLMParams(),
        )
    }

    private fun endpointFor(id: String): LlmEndpoint? =
        LlmEndpointCatalog.builtinById(id)
            ?: CustomEndpointStore(EncryptedJsonStore(app, CustomEndpointStore.STORE_NAME)).allEndpoints()
                .firstOrNull { it.id == id }
}
