package com.aura.aura_ui.agent.llm

import kotlinx.serialization.Serializable

/** How reasoning is REQUESTED from the provider on the OpenAI-compat wire. */
@Serializable
enum class ReasoningRequestStyle {
    /** Provider needs nothing special in the request. */
    NONE,

    /** Gemini-direct: `extra_body.google.thinking_config` (see [GeminiThinkingConfig]). */
    GEMINI_THINKING_CONFIG,

    /**
     * OpenAI / Groq reasoning models: top-level `reasoning_effort: "low|medium|high"` (D2 piece 2).
     * The chat API has no "off", so [ReasoningLevel.OFF]/[ReasoningLevel.DEFAULT] send nothing.
     */
    OPENAI_REASONING_EFFORT,

    /**
     * OpenRouter unified reasoning param: `reasoning: {"effort": …}` or `{"enabled": false}`
     * (openrouter.ai/docs/…/reasoning-tokens, verified 2026-07-13). The safest control — OpenRouter
     * normalizes it across every downstream provider. [D2 piece 2]
     */
    OPENROUTER_REASONING,
}

/** How the provider RETURNS reasoning on the OpenAI-compat wire. */
@Serializable
enum class ReasoningResponseStyle {
    /** No reasoning payload on the wire. */
    NONE,

    /**
     * Gemini-direct: per-tool-call `extra_content.google.thought_signature` that must be
     * echoed back (see [GeminiThoughtSignatureRelay]).
     */
    GEMINI_EXTRA_CONTENT,

    /**
     * OpenRouter: message-level `reasoning_details` array that must be passed back
     * unmodified on the assistant message (see [OpenRouterReasoningDetailsEcho]).
     */
    REASONING_DETAILS,
}

/**
 * Declared wire-dialect facts for one LLM provider (ADDITIONS.md § 2, D1).
 *
 * The interceptor chain in [OpenAiCompatProvider.client] is assembled FROM these facts
 * instead of each shim sniffing hostnames: the profile decides which shims exist in the
 * chain (add-time gating); the shims' internal host checks remain as belt-and-braces.
 * This is what lets a future custom endpoint (D2) declare "I serve Gemini 3" and get the
 * right shims without a code change — and what fixes reasoning models via OpenRouter
 * today, whose reasoning echo (`reasoning_details`) was previously handled nowhere
 * (B5/B6/B13 root).
 *
 * A data class, not an enum: D2's custom endpoints will construct profiles from user
 * configuration rather than pick from a fixed set.
 */
@Serializable
data class ProviderProfile(
    val reasoningRequestStyle: ReasoningRequestStyle = ReasoningRequestStyle.NONE,
    val reasoningResponseStyle: ReasoningResponseStyle = ReasoningResponseStyle.NONE,
    /** Gemini-direct: round-trip per-tool-call thought signatures or the turn 400s. */
    val requiresSignatureEcho: Boolean = false,
    /** Gemini-direct: thought-only responses omit `finish_reason`; Koog requires it. */
    val needsFinishReasonRepair: Boolean = false,
    /**
     * Whether the endpoint **accepts the `parallel_tool_calls` request field** at all.
     *
     * P11 — named for what it means, not what it sounds like. The old name
     * (`supportsParallelTools`) read as "the agent uses parallel tool calls", when in fact it
     * gates an interceptor that sets `parallel_tool_calls=false` — i.e. true here means the
     * agent DISABLES tool batching on this endpoint. Correct then, actively misleading to read.
     */
    val acceptsParallelToolCallsField: Boolean = true,
) {
    /**
     * True when this provider can carry a user-set thinking level ([GenerationConfig.reasoning]),
     * so the settings UI shows the thinking control. Temperature is universal and gated separately.
     */
    fun supportsReasoningControl(): Boolean = reasoningRequestStyle != ReasoningRequestStyle.NONE

    companion object {
        /**
         * Groq: strict, plain OpenAI wire, and it accepts OpenAI's `reasoning_effort` on its reasoning
         * models (gpt-oss, qwen3). Reasoning is **per-model** on Groq — the flagship agent model
         * (Llama 4) is non-reasoning and would 400 on `reasoning_effort` — so the control is gated by
         * the selected model's [ModelInfo.reasoningCapable] (UI) AND a runtime wire-gate
         * ([ModelCatalog.reasoningCapableForWire]) that strips reasoning for non-reasoning ids. That
         * per-model gating is what makes advertising the style here safe. Temperature still applies.
         */
        val GROQ = ProviderProfile(
            reasoningRequestStyle = ReasoningRequestStyle.OPENAI_REASONING_EFFORT,
        )

        /** OpenRouter: reasoning models return `reasoning_details` that must be echoed, and the
         * unified `reasoning` request param controls the thinking level (D2 piece 2). */
        val OPENROUTER = ProviderProfile(
            reasoningRequestStyle = ReasoningRequestStyle.OPENROUTER_REASONING,
            reasoningResponseStyle = ReasoningResponseStyle.REASONING_DETAILS,
        )

        /** Gemini via its OpenAI-compat surface: all three Gemini shims apply. */
        val GEMINI = ProviderProfile(
            reasoningRequestStyle = ReasoningRequestStyle.GEMINI_THINKING_CONFIG,
            reasoningResponseStyle = ReasoningResponseStyle.GEMINI_EXTRA_CONTENT,
            requiresSignatureEcho = true,
            needsFinishReasonRepair = true,
        )

        /** OpenAI: plain wire + `reasoning_effort` for its o-series / GPT-5 reasoning models (D2 piece 2). */
        val OPENAI = ProviderProfile(
            reasoningRequestStyle = ReasoningRequestStyle.OPENAI_REASONING_EFFORT,
        )

        /** Anthropic via its OpenAI-compat surface: plain wire for now (compat surface unverified). */
        val ANTHROPIC = ProviderProfile()
    }
}
