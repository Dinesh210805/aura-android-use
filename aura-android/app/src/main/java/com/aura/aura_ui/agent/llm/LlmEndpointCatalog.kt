package com.aura.aura_ui.agent.llm

/**
 * The built-in LLM endpoints AURA ships (D2 piece 1). Verified set only: Groq/OpenRouter/Gemini
 * are proven live; OpenAI/Anthropic are their documented OpenAI-compatible surfaces (confirm the
 * exact URLs against the vendor docs before release — piece-1 note). DeepSeek/Together/Fireworks/
 * Ollama are piece 1b.
 *
 * Built-in ids are the legacy lowercased `LlmProvider` names — the `ProviderKeyStore` migration
 * contract ([LlmEndpointCatalogTest]).
 */
object LlmEndpointCatalog {

    const val GROQ_ID = "groq"
    const val OPENROUTER_ID = "openrouter"
    const val GEMINI_ID = "gemini"
    const val OPENAI_ID = "openai"
    const val ANTHROPIC_ID = "anthropic"

    /**
     * Groq's multimodal Llama 4 Scout. Still the app-wide fallback and the model the
     * Groq-specific vision path pins explicitly, but per-provider defaults now live on
     * [LlmEndpoint.defaultModelId] — sending this Groq id to Gemini or Anthropic is a
     * guaranteed 400, which is exactly what the old single-constant fallback did.
     */
    const val DEFAULT_MODEL_ID = "meta-llama/llama-4-scout-17b-16e-instruct"

    /**
     * Gemini's cheap/fast tier for agent work. Verified present on 2026-07-30 by
     * enumerating a live key through `ModelCatalog.fetch` (debug harness `brainmodels`
     * mode) rather than from memory — model ids age badly and a wrong one fails silently
     * at runtime, not at build time.
     *
     * Pinned rather than using the `gemini-flash-lite-latest` alias so a Google-side
     * promotion can't silently change the agent's brain under a user who never opted in.
     */
    const val GEMINI_DEFAULT_MODEL_ID = "gemini-3.5-flash-lite"

    private val GROQ = LlmEndpoint(
        id = GROQ_ID,
        displayName = "Groq",
        baseUrl = "https://api.groq.com",
        chatCompletionsPath = "/openai/v1/chat/completions",
        modelsUrl = "https://api.groq.com/openai/v1/models",
        authScheme = AuthScheme.BEARER,
        modelListStyle = ModelListStyle.OPENAI_DATA,
        visionInference = VisionInference.LLAMA4_FAMILY,
        reasoningInference = ReasoningInference.GROQ_FAMILY,
        profile = ProviderProfile.GROQ,
        isBuiltIn = true,
        keyHint = "gsk_...",
        keyUrl = "https://console.groq.com/keys",
        defaultModelId = DEFAULT_MODEL_ID,
    )

    private val OPENROUTER = LlmEndpoint(
        id = OPENROUTER_ID,
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai",
        chatCompletionsPath = "/api/v1/chat/completions",
        modelsUrl = "https://openrouter.ai/api/v1/models",
        authScheme = AuthScheme.BEARER,
        modelListStyle = ModelListStyle.OPENAI_DATA,
        visionInference = VisionInference.OPENROUTER_MODALITY,
        reasoningInference = ReasoningInference.OPENROUTER_SUPPORTED_PARAMS,
        profile = ProviderProfile.OPENROUTER,
        isBuiltIn = true,
        keyHint = "sk-or-...",
        keyUrl = "https://openrouter.ai/keys",
    )

    private val GEMINI = LlmEndpoint(
        id = GEMINI_ID,
        displayName = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com",
        chatCompletionsPath = "/v1beta/openai/chat/completions",
        modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models",
        authScheme = AuthScheme.X_GOOG_API_KEY,
        modelListStyle = ModelListStyle.GEMINI_MODELS,
        visionInference = VisionInference.GEMINI_GENERATION,
        reasoningInference = ReasoningInference.GEMINI_GENERATION,
        profile = ProviderProfile.GEMINI,
        isBuiltIn = true,
        keyHint = "AIza...",
        keyUrl = "https://aistudio.google.com/apikey",
        defaultModelId = GEMINI_DEFAULT_MODEL_ID,
    )

    private val OPENAI = LlmEndpoint(
        id = OPENAI_ID,
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com",
        chatCompletionsPath = "/v1/chat/completions",
        modelsUrl = "https://api.openai.com/v1/models",
        authScheme = AuthScheme.BEARER,
        modelListStyle = ModelListStyle.OPENAI_DATA,
        visionInference = VisionInference.OPENAI_FAMILY,
        reasoningInference = ReasoningInference.OPENAI_OSERIES,
        profile = ProviderProfile.OPENAI,
        isBuiltIn = true,
        keyHint = "sk-...",
        keyUrl = "https://platform.openai.com/api-keys",
    )

    /**
     * Anthropic — **two different surfaces, verified live 2026-09-22** (the piece-1 note said
     * "confirm before release"; it never was, and both halves were wrong).
     *
     *  - Chat rides their OpenAI-compat layer at `/v1/chat/completions` with Bearer. Correct as
     *    shipped. Note their docs call that layer "not a long-term or production-ready solution":
     *    it never returns `prompt_tokens_details` (so no cache accounting), drops thinking output,
     *    and ignores `reasoning_effort` — which is why [ProviderProfile.ANTHROPIC] sends none.
     *  - `/v1/models` is a **native** endpoint and ignores Bearer entirely — hence
     *    [AuthScheme.ANTHROPIC_X_API_KEY]. It also spells the context window `max_input_tokens`,
     *    which `ModelCatalog.parseOpenAiShape` now falls back to.
     */
    private val ANTHROPIC = LlmEndpoint(
        id = ANTHROPIC_ID,
        displayName = "Anthropic (Claude)",
        baseUrl = "https://api.anthropic.com",
        chatCompletionsPath = "/v1/chat/completions",
        modelsUrl = "https://api.anthropic.com/v1/models",
        authScheme = AuthScheme.ANTHROPIC_X_API_KEY,
        modelListStyle = ModelListStyle.OPENAI_DATA,
        visionInference = VisionInference.ALL_MODELS,
        profile = ProviderProfile.ANTHROPIC,
        isBuiltIn = true,
        keyHint = "sk-ant-...",
        keyUrl = "https://console.anthropic.com/settings/keys",
    )

    // ── Plain OpenAI-wire presets (2026-09-22) ────────────────────────────────
    //
    // A built-in endpoint is a BOOKMARK, not a capability: the custom-endpoint screen already
    // reaches any OpenAI-compatible URL. So the bar for adding one is only "should a user of this
    // provider have to type the base URL themselves", and the answer for a known provider is no.
    // An earlier cut of this list filtered on "has a free tier", which was designing for one
    // developer's key rather than for users, who bring paid keys too.
    //
    // All of them declare `ProviderProfile()` — plain wire, no shims. Each carries ONLY facts that
    // were checked, and `defaultModelId` is deliberately left blank everywhere: a stale model id
    // fails at runtime with a 400, not at build time, and blank makes the UI prompt instead.
    //
    // Paths were confirmed by probing the real path AND a deliberately wrong path on the same host,
    // requiring a DIFFERENT status from each. A lone 401 proves nothing — DeepSeek answers 401 for
    // any path at all, which is why it is called out below.

    private fun openAiWire(
        id: String,
        displayName: String,
        baseUrl: String,
        path: String = "/v1/chat/completions",
        modelsUrl: String = "$baseUrl/v1/models",
        keyHint: String = "",
        keyUrl: String = "",
    ) = LlmEndpoint(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        chatCompletionsPath = path,
        modelsUrl = modelsUrl,
        authScheme = AuthScheme.BEARER,
        modelListStyle = ModelListStyle.OPENAI_DATA,
        // No modality field on any of these listings, so capability is inferred from the model id.
        visionInference = VisionInference.GENERIC,
        profile = ProviderProfile(),
        isBuiltIn = true,
        keyHint = keyHint,
        keyUrl = keyUrl,
    )

    /** Mistral. Pixtral models are vision-capable and `genericVision` names them. */
    private val MISTRAL = openAiWire(
        id = "mistral",
        displayName = "Mistral",
        baseUrl = "https://api.mistral.ai",
        keyUrl = "https://console.mistral.ai/api-keys",
    )

    /** Alibaba Qwen via DashScope's OpenAI-compatible mode. Qwen-VL matches `genericVision`'s `-vl`. */
    private val DASHSCOPE = openAiWire(
        id = "dashscope",
        displayName = "Qwen (DashScope)",
        baseUrl = "https://dashscope-intl.aliyuncs.com",
        path = "/compatible-mode/v1/chat/completions",
        modelsUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models",
        keyHint = "sk-...",
        keyUrl = "https://bailian.console.alibabacloud.com/?apiKey=1",
    )

    /** Cerebras. Text-only models — the agent's `read_screen` path covers them. */
    private val CEREBRAS = openAiWire(
        id = "cerebras",
        displayName = "Cerebras",
        baseUrl = "https://api.cerebras.ai",
        keyHint = "csk-...",
        keyUrl = "https://cloud.cerebras.ai",
    )

    /** Z.ai (GLM). Note the non-standard `/api/paas/v4` prefix — `/v1/...` 404s there. */
    private val ZAI = openAiWire(
        id = "zai",
        displayName = "Z.ai (GLM)",
        baseUrl = "https://api.z.ai",
        path = "/api/paas/v4/chat/completions",
        modelsUrl = "https://api.z.ai/api/paas/v4/models",
        keyUrl = "https://z.ai/manage-apikey/apikey-list",
    )

    /** xAI (Grok). The only probe that answered 400 rather than 401 — it parsed the body. */
    private val XAI = openAiWire(
        id = "xai",
        displayName = "xAI (Grok)",
        baseUrl = "https://api.x.ai",
        keyHint = "xai-...",
        keyUrl = "https://console.x.ai",
    )

    /** Together AI — an aggregator, so its listing spans many model families. */
    private val TOGETHER = openAiWire(
        id = "together",
        displayName = "Together AI",
        baseUrl = "https://api.together.xyz",
        keyUrl = "https://api.together.xyz/settings/api-keys",
    )

    /** Moonshot (Kimi). */
    private val MOONSHOT = openAiWire(
        id = "moonshot",
        displayName = "Moonshot (Kimi)",
        baseUrl = "https://api.moonshot.ai",
        keyHint = "sk-...",
        keyUrl = "https://platform.moonshot.ai/console/api-keys",
    )

    /**
     * DeepSeek — the one preset here whose path could not be confirmed by probe.
     *
     * DeepSeek authenticates BEFORE routing, so `/v9/nope` and the real path both answer 401 with
     * and without a token. That makes the probe uninformative rather than negative, which is a
     * different thing from Anthropic (where the evidence actively contradicted what shipped), so
     * this goes in on the strength of DeepSeek's documented URL and is flagged for a key test.
     * Note `/models` sits at the root, not under `/v1`.
     */
    private val DEEPSEEK = openAiWire(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        modelsUrl = "https://api.deepseek.com/models",
        keyHint = "sk-...",
        keyUrl = "https://platform.deepseek.com/api_keys",
    )

    // Deliberately NOT here:
    //  - Fireworks: its documented path `/inference/v1/chat/completions` answered 404, the same as
    //    the control. That is weak evidence AGAINST, so it waits for someone with a key.
    //  - Bedrock: not OpenAI-wire at all. It needs AWS SigV4 request signing, which is not a header
    //    we can set — the only provider excluded for architectural rather than evidential reasons.
    //  - Ollama: a built-in pointing at `http://localhost:11434` is a bookmark to the phone itself,
    //    which is not where anyone runs Ollama, and a LAN address additionally needs a cleartext
    //    network-security exemption. The custom-endpoint screen already covers it properly, with
    //    the user's own IP and `requiresKey = false`.

    val BUILTINS: List<LlmEndpoint> = listOf(
        GROQ, OPENROUTER, GEMINI, OPENAI, ANTHROPIC,
        MISTRAL, DASHSCOPE, CEREBRAS, ZAI, XAI, TOGETHER, MOONSHOT, DEEPSEEK,
    )

    fun builtinById(id: String): LlmEndpoint? = BUILTINS.firstOrNull { it.id == id }
}
