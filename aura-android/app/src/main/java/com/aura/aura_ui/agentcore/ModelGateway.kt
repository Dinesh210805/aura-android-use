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
import com.aura.aura_ui.agent.llm.ThinkingSupport
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
    /**
     * Request settings for every prompt sent to this model. Empty on the OpenAI-compatible wire
     * (the HTTP shims carry the user's settings); on the native Claude wire they hold the output
     * cap, thinking and caching, so every prompt must be built with them.
     */
    val params: LLMParams,
) {
    fun executor(): PromptExecutor = MultiLLMPromptExecutor(model.provider to client)

    /**
     * One request with no tools and no history: summaries, titles, classification.
     * - Fails: returns null on any provider error. A side job must never fail the turn it serves.
     */
    suspend fun ask(instruction: String, input: String): String? = runCatching {
        val reply = client.execute(
            prompt("aura-aux", params) {
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
 * - Contract: [ModelRole.FAST] resolves to the main model at its cheapest thinking level;
 *   [ModelRole.LOCAL] resolves to null until an on-device runtime is wired in ([LocalModel] covers
 *   the background jobs that must work without one).
 */
class SavedSettingsModelProvider(context: Context) : ModelProvider {
    private val app = context.applicationContext

    override fun resolve(role: ModelRole): ResolvedModel? = when (role) {
        ModelRole.MAIN -> runCatching { saved(ProviderKeyStore(app).getSelectedEndpointId(), sideJob = false) }.getOrNull()
        ModelRole.FAST -> runCatching { saved(ProviderKeyStore(app).getSelectedEndpointId(), sideJob = true) }.getOrNull()
        ModelRole.LOCAL -> null
        ModelRole.FALLBACK -> runCatching { fallback() }.getOrNull()
    }

    /** The first other provider with a key and a model, at the user's saved settings for it. */
    private fun fallback(): ResolvedModel? {
        val keys = ProviderKeyStore(app)
        val main = keys.getSelectedEndpointId()
        val others = LlmEndpointCatalog.BUILTINS.map { it.id } +
            CustomEndpointStore(EncryptedJsonStore(app, CustomEndpointStore.STORE_NAME)).allEndpoints().map { it.id }
        return others.distinct().filter { it != main }.firstNotNullOfOrNull { id -> runCatching { saved(id, sideJob = false) }.getOrNull() }
    }

    /**
     * The saved model. [sideJob] runs it at its cheapest thinking level: a chat title or a summary
     * must not pay for the reasoning the user chose for tasks.
     */
    private fun saved(endpointId: String, sideJob: Boolean): ResolvedModel? {
        val keys = ProviderKeyStore(app)
        val endpoint = endpointFor(endpointId) ?: return null
        val apiKey = if (endpoint.requiresKey) keys.getKey(endpointId) ?: return null else ""
        // A keyless endpoint (a local server) counts only when the user picked a model for it.
        if (!endpoint.requiresKey && endpointId != keys.getSelectedEndpointId() && keys.getSelectedModel(endpointId) == null) return null
        val modelId = keys.getSelectedModel(endpointId) ?: endpoint.defaultModelId.ifBlank { return null }
        val vision = keys.getSelectedModelVisionCapable(endpointId)
        val reasoning = keys.getSelectedModelReasoning(endpointId)
        val canDisable = keys.getSelectedModelCanDisableThinking(endpointId)
        val saved: GenerationConfig = keys.getGeneration(endpointId)
        val generation = if (sideJob) {
            saved.copy(reasoning = ThinkingSupport.lightest(ThinkingSupport.levelsFor(endpoint, modelId, reasoning, canDisable)))
        } else {
            saved
        }
        val chat = ProviderRegistry.chatModel(endpoint, apiKey, modelId, vision, generation, reasoning, canDisable)
        return ResolvedModel(
            model = chat.model,
            client = chat.client,
            label = "${endpoint.displayName} / $modelId",
            contextWindow = keys.getSelectedModelContextWindow(endpointId),
            // Unknown means "send images": withholding them from a model that can see is the worse error.
            vision = vision != false,
            params = chat.params,
        )
    }

    private fun endpointFor(id: String): LlmEndpoint? =
        LlmEndpointCatalog.builtinById(id)
            ?: CustomEndpointStore(EncryptedJsonStore(app, CustomEndpointStore.STORE_NAME)).allEndpoints()
                .firstOrNull { it.id == id }
}

/**
 * The main model's client that switches to [fallback] for the rest of the turn when the main
 * provider is down.
 *
 * - Contract: a call that fails with a rate limit, a server error or a network error (after the
 *   main client's own retries) is sent to [fallback] instead, and so is every later call; [onSwitch]
 *   is called once with the reason. A rejected key (401/403) or any other error is thrown as it is:
 *   the user must hear about a bad key, not have it hidden behind another provider.
 * - Why per turn: switching back and forth would mix two models' styles inside one task.
 */
class FallbackClient(
    private val main: LLMClient,
    private val fallback: ResolvedModel,
    private val onSwitch: suspend (reason: String) -> Unit,
) : LLMClient() {
    @Volatile
    private var switched = false

    override fun llmProvider(): ai.koog.prompt.llm.LLMProvider = main.llmProvider()

    override suspend fun execute(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): Message.Assistant {
        if (!switched) {
            try {
                return main.execute(prompt, model, tools)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = ProviderOutage.reason(e) ?: throw e
                switched = true
                onSwitch(reason)
            }
        }
        return fallback.client.execute(prompt.withParams(fallback.params), fallback.model, tools)
    }

    /** Streams from whichever provider the turn is on; a failed stream falls back to [execute] in the engine. */
    override fun executeStreaming(
        prompt: ai.koog.prompt.Prompt,
        model: LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): kotlinx.coroutines.flow.Flow<ai.koog.prompt.streaming.StreamFrame> =
        if (switched) fallback.client.executeStreaming(prompt.withParams(fallback.params), fallback.model, tools) else main.executeStreaming(prompt, model, tools)

    override suspend fun moderate(prompt: ai.koog.prompt.Prompt, model: LLModel): ai.koog.prompt.dsl.ModerationResult =
        main.moderate(prompt, model)

    // Both clients belong to their ResolvedModels, which outlive this turn.
    override fun close() = Unit
}

/** Which failures mean the provider is down rather than the request or the key being wrong. */
object ProviderOutage {
    private val AUTH = Regex("\\b(401|403)\\b|(?i)unauthori[sz]ed|invalid api key|permission denied")
    private val QUOTA = Regex("\\b429\\b|(?i)rate limit|quota|resource_exhausted|overloaded")
    private val SERVER = Regex("\\b(500|502|503|504|529)\\b|(?i)service unavailable|bad gateway|internal server error")
    private val NETWORK = Regex("(?i)UnknownHost|ConnectException|SocketTimeout|timed? ?out|unable to resolve host|SSLException")

    /** "rate limited", "a server error" or "unreachable"; null when this is not an outage. */
    fun reason(e: Throwable): String? {
        val detail = generateSequence(e) { it.cause }.take(4).joinToString(" ") { "${it::class.simpleName} ${it.message.orEmpty()}" }
        return when {
            AUTH.containsMatchIn(detail) -> null
            QUOTA.containsMatchIn(detail) -> "rate limited"
            SERVER.containsMatchIn(detail) -> "having a server error"
            NETWORK.containsMatchIn(detail) -> "unreachable"
            else -> null
        }
    }
}
