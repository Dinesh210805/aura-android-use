package com.aura.aura_ui.agent.llm

import kotlinx.serialization.Serializable

/** How [ModelCatalog] authenticates the `/models` fetch. The chat leg is always Bearer via Koog. */
@Serializable
/**
 * How the **model-listing** fetch authenticates. Deliberately not the chat call's business: chat
 * always goes through Koog's OpenAI client, which sends `Authorization: Bearer`. Gemini has proved
 * that split works since D2 — `x-goog-api-key` for `/models`, Bearer for its OpenAI-compat chat
 * surface — and [ANTHROPIC_X_API_KEY] relies on the same separation.
 */
enum class AuthScheme {
    BEARER,
    X_GOOG_API_KEY,

    /**
     * Anthropic's `/v1/models` is a **native** endpoint, not part of their OpenAI-compat layer. It
     * ignores `Authorization` entirely and requires `x-api-key` plus the mandatory
     * `anthropic-version` header. Verified live 2026-09-22: a Bearer token gets
     * `{"error":{"type":"authentication_error","message":"invalid x-api-key"}}`.
     *
     * Until this existed the endpoint was declared `BEARER`, so the Anthropic model picker could
     * never authenticate and always came back empty — shipped that way because the endpoint was
     * added from documentation. Its own KDoc said "confirm before release"; nobody did.
     */
    ANTHROPIC_X_API_KEY,
    NONE,
}

/** Response shape of the provider's `/models` listing. */
@Serializable
enum class ModelListStyle { OPENAI_DATA, GEMINI_MODELS }

/** How [ModelCatalog] decides a model can read screenshots (providers report modality inconsistently). */
@Serializable
enum class VisionInference { LLAMA4_FAMILY, OPENROUTER_MODALITY, GEMINI_GENERATION, OPENAI_FAMILY, ALL_MODELS, GENERIC }

/**
 * How [ModelCatalog] decides a model can *think* (reason), and how the thinking control is gated
 * per-model. Providers report this as inconsistently as modality:
 *  - [OPENROUTER_SUPPORTED_PARAMS] — exact: the model's `supported_parameters` array lists `reasoning`.
 *  - [OPENAI_OSERIES] / [GROQ_FAMILY] / [GEMINI_GENERATION] — id heuristics (the vendor exposes no flag).
 *  - [NONE] — no reasoning control (Anthropic compat unverified; custom endpoints).
 */
@Serializable
enum class ReasoningInference { NONE, OPENROUTER_SUPPORTED_PARAMS, OPENAI_OSERIES, GROQ_FAMILY, GEMINI_GENERATION }

/**
 * One configured LLM endpoint the on-device agent can target (D2). Replaces the frozen
 * `LlmProvider` enum: built-ins live in [LlmEndpointCatalog]; users add OpenAI-compatible
 * [isBuiltIn]=false endpoints. Every per-provider wire fact the D1 shim chain and the model
 * catalog need is declared here, so generic code reads the endpoint instead of switching on a
 * provider name.
 *
 * [id] is the stable persistence key (see `ProviderKeyStore`); built-in ids equal the legacy
 * lowercased enum names for zero-migration upgrades. Custom ids are `"custom:<slug>"`.
 */
@Serializable
data class LlmEndpoint(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val chatCompletionsPath: String,
    val modelsUrl: String,
    val authScheme: AuthScheme,
    val modelListStyle: ModelListStyle,
    val visionInference: VisionInference,
    /** How per-model reasoning capability is decided. Defaults to [ReasoningInference.NONE] so a
     * serialized custom endpoint without this field (older store) deserializes to "no thinking". */
    val reasoningInference: ReasoningInference = ReasoningInference.NONE,
    val profile: ProviderProfile,
    val isBuiltIn: Boolean,
    val keyHint: String = "",
    val keyUrl: String = "",
    /** Local endpoints (Ollama, piece 1b) need no key; the UI hides the key field + consent. */
    val requiresKey: Boolean = true,
    /**
     * Model used when the user has configured this provider but never picked a model.
     *
     * Per-endpoint on purpose: the fallback used to be a single global constant holding a
     * *Groq* model id, so configuring Gemini (or any non-Groq provider) and not choosing a
     * model sent `meta-llama/llama-4-scout-…` to that provider — a guaranteed 400.
     *
     * Empty for custom endpoints deserialized from an older store, where no sensible default
     * can be invented; callers treat blank as "no default" and must prompt the user.
     */
    val defaultModelId: String = "",
)
