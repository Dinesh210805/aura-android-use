package com.aura.aura_ui.agent.llm

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.aura.aura_ui.agent.strategy.AgentContextConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.TimeUnit

/**
 * Claude through Koog's native Anthropic client: extended thinking, thinking blocks echoed with
 * their signatures on tool turns, automatic prompt caching and real token counts. Anthropic's
 * OpenAI-compatible layer carries none of these.
 *
 * - Contract: build [model] first and pass the same instance to [client] and [params]. Koog's
 *   client resolves the wire id from a model → id map and throws "Unsupported model" for any model
 *   it was not given, so [client] maps exactly this one, under the id the user picked.
 * - Fails: provider errors surface from the call after Koog's retries ([AgentRetryPolicy]); a
 *   stalled socket fails on [AgentHttpTimeouts] like every other provider.
 * - Change together: [ThinkingSupport] (the levels Claude is offered), [ProviderRegistry].
 */
internal object AnthropicNative {

    /** Koog's own definition when it knows the id (exact capabilities and limits), else one built from the listing. */
    fun model(modelId: String, vision: Boolean?): LLModel =
        AnthropicModels.models.firstOrNull { it.id == modelId } ?: LLModel(
            provider = LLMProvider.Anthropic,
            id = modelId,
            capabilities = buildList {
                add(LLMCapability.Temperature)
                add(LLMCapability.Tools)
                add(LLMCapability.ToolChoice)
                add(LLMCapability.Document)
                add(LLMCapability.PromptCaching)
                // Unknown means "send images", as on every other wire: withholding them is the worse error.
                if (vision != false) add(LLMCapability.Vision.Image)
                if (ModelCatalog.anthropicReasoning(modelId)) add(LLMCapability.Thinking)
            },
        )

    fun client(apiKey: String, baseUrl: String, model: LLModel, contextConfig: AgentContextConfig): LLMClient {
        val settings = AnthropicClientSettings(modelVersionsMap = mapOf(model to model.id), baseUrl = baseUrl)
        val base = AnthropicLLMClient(apiKey, settings, KtorKoogHttpClient.Factory(httpClient(AgentHttpTimeouts())))
        return RetryingLLMClient(base, AgentRetryPolicy.configFor(contextConfig))
    }

    /**
     * The request settings for one turn.
     * - Contract: always sets `max_tokens` (Koog would send 2,048, which cuts a long tool call),
     *   keeps it above the thinking budget as Anthropic requires, sends a temperature only with
     *   thinking off (Claude rejects one while thinking), and turns on automatic prompt caching so
     *   the system prompt and tool list are not billed in full on every step.
     */
    fun params(model: LLModel, generation: GenerationConfig): LLMParams {
        val budget = budgetFor(generation.reasoning)
        val ceiling = model.maxOutputTokens?.toInt()?.coerceAtMost(MAX_OUTPUT) ?: MAX_OUTPUT
        return AnthropicParams(
            temperature = generation.temperature.takeIf { budget == null },
            maxTokens = if (budget == null) ceiling else maxOf(ceiling, budget + ANSWER_ROOM),
            thinking = budget?.let { AnthropicThinking.Enabled(budgetTokens = it) },
            cacheControl = AnthropicCacheControl.Default,
        )
    }

    /** Thinking tokens per level; null sends no thinking block (Claude's default is off). The API minimum is 1,024. */
    internal fun budgetFor(level: ReasoningLevel): Int? = when (level) {
        ReasoningLevel.LOW -> 2_048
        ReasoningLevel.MEDIUM -> 6_144
        ReasoningLevel.HIGH -> 12_288
        ReasoningLevel.OFF, ReasoningLevel.DEFAULT -> null
    }

    /** Output cap for a turn. Large enough for any tool call; Anthropic bills what is generated, not the cap. */
    private const val MAX_OUTPUT = 16_384

    /** Room left for the answer and tool call after the thinking budget. */
    private const val ANSWER_ROOM = 4_096

    private fun httpClient(timeouts: AgentHttpTimeouts): HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = timeouts.requestMs
            connectTimeoutMillis = timeouts.connectMs
            socketTimeoutMillis = timeouts.socketMs
        }
        engine {
            config {
                connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
                readTimeout(timeouts.socketMs, TimeUnit.MILLISECONDS)
                writeTimeout(timeouts.socketMs, TimeUnit.MILLISECONDS)
                callTimeout(timeouts.requestMs, TimeUnit.MILLISECONDS)
                retryOnConnectionFailure(true)
            }
        }
    }
}
