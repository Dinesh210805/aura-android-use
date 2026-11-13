package com.aura.aura_ui.agent.llm

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.aura.aura_ui.agent.strategy.AgentContextConfig

/**
 * Resolves an [LlmEndpoint] + API key to a Koog [LLMClient], and an [LlmEndpoint] + model id to a
 * Koog [LLModel] (D2). Every endpoint is reached through the one OpenAI-wire client
 * ([OpenAiCompatProvider]); the D1 [ProviderProfile] carried on the endpoint assembles the correct
 * interceptor chain (Gemini shims, OpenRouter reasoning-details echo, the parallel-tools splice).
 *
 * Replaces the pre-D2 fixed `LlmProvider` enum: providers are now data ([LlmEndpointCatalog] +
 * user-defined custom endpoints), so any OpenAI-compatible endpoint is reachable without a code
 * change.
 */
object ProviderRegistry {

    /** A model ready to call: the definition, the client that serves it and the request settings to send. */
    class ChatModel(val model: LLModel, val client: LLMClient, val params: LLMParams)

    /**
     * The model the agent chats with, on the endpoint's own wire ([LlmEndpoint.wire]).
     *
     * - Contract: the thinking level is clamped to what this model accepts
     *   ([ThinkingSupport.levelsFor]) and a temperature the model would reject is dropped, so a
     *   setting saved for another model can never fail a call. On the OpenAI-compatible wire the
     *   settings ride the HTTP shims and [ChatModel.params] is empty; on the native Claude wire they
     *   are [ChatModel.params], which the caller must put on its prompt.
     * - Why separate from [clientFor]: the native client must be built from the same [LLModel] it
     *   serves (see [AnthropicNative]). [clientFor] and [modelFor] stay on the compatible wire for the
     *   older callers that build the two apart.
     */
    fun chatModel(
        endpoint: LlmEndpoint,
        apiKey: String,
        modelId: String,
        visionCapable: Boolean?,
        generation: GenerationConfig,
        reasoning: Boolean? = null,
        canDisableThinking: Boolean? = null,
        contextConfig: AgentContextConfig = AgentContextConfig.DEFAULT,
    ): ChatModel {
        val level = ThinkingSupport.clamp(generation.reasoning, ThinkingSupport.levelsFor(endpoint, modelId, reasoning, canDisableThinking))
        val settled = generation.copy(
            reasoning = level,
            temperature = generation.temperature.takeIf { ThinkingSupport.temperatureAllowed(endpoint, modelId, level) },
        )
        return when (endpoint.wire) {
            ChatWire.ANTHROPIC_NATIVE -> {
                val model = AnthropicNative.model(modelId, visionCapable)
                ChatModel(model, AnthropicNative.client(apiKey, endpoint.baseUrl, model, contextConfig), AnthropicNative.params(model, settled))
            }
            ChatWire.OPENAI_COMPAT -> ChatModel(
                modelFor(endpoint, modelId, visionCapable),
                OpenAiCompatProvider.client(apiKey, endpoint.baseUrl, endpoint.chatCompletionsPath, endpoint.profile, settled, contextConfig),
                LLMParams(),
            )
        }
    }

    fun clientFor(
        endpoint: LlmEndpoint,
        apiKey: String,
        modelId: String,
        generation: GenerationConfig = GenerationConfig(),
        // Threaded rather than defaulted inside the provider so the retry policy has exactly one
        // source (F2). Defaulted here so callers that do not tune context discipline are unchanged.
        contextConfig: AgentContextConfig = AgentContextConfig.DEFAULT,
    ): LLMClient =
        OpenAiCompatProvider.client(
            apiKey,
            endpoint.baseUrl,
            endpoint.chatCompletionsPath,
            endpoint.profile,
            gateReasoning(endpoint, modelId, generation),
            contextConfig,
        )

    /**
     * Runtime wire-gate (D2 truthfulness): strip a user-set reasoning level when the selected model
     * can't honor it, so a level configured for a reasoning model never rides along onto a
     * non-reasoning one on the same endpoint (e.g. Groq Llama-4 must never receive `reasoning_effort`).
     * Only ever REMOVES a field — a default config passes through byte-identical.
     */
    internal fun gateReasoning(endpoint: LlmEndpoint, modelId: String, generation: GenerationConfig): GenerationConfig =
        if (generation.reasoning != ReasoningLevel.DEFAULT &&
            !ModelCatalog.reasoningCapableForWire(endpoint, modelId)
        ) {
            generation.copy(reasoning = ReasoningLevel.DEFAULT)
        } else {
            generation
        }

    /**
     * [visionCapable] is tri-state and defaults to null = "declare vision" — see
     * [OpenAiCompatProvider.modelFor] for why unknown must never withhold the capability.
     */
    fun modelFor(endpoint: LlmEndpoint, modelId: String, visionCapable: Boolean? = null): LLModel =
        OpenAiCompatProvider.modelFor(modelId, visionCapable)
}
