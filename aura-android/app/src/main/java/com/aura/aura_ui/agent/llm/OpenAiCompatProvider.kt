package com.aura.aura_ui.agent.llm

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.aura.aura_ui.agent.strategy.AgentContextConfig
import android.util.Log
import com.aura.aura_ui.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/**
 * Shared builder for **OpenAI-wire-compatible** providers (Groq, OpenRouter).
 *
 * Both speak the same `/v1/chat/completions` protocol, so they reuse Koog's
 * [OpenAILLMClient] — only the base URL differs. This centralises the two
 * Android-specific concerns so each provider doesn't re-wire them:
 *
 *  1. **Explicit OkHttp engine** — Koog's `KoogHttpClient.Factory` is normally
 *     auto-resolved from a classpath provider, but R8 drops the AAR's
 *     `META-INF/services` registration, so we hand Koog a ready factory. [SHIM #1]
 *  2. **Tool-arg repair** — [KoogOpenAiToolArgRepair] reverses Koog 1.0.0's
 *     double-encoding of tool-call `arguments` (#2095/#2096) that 400s strict
 *     OpenAI-compat backends. Self-disabling. [SHIM #2]
 *
 * Every further shim in the chain is provider-specific and is added only when the
 * caller's [ProviderProfile] declares the need (D1, ADDITIONS.md § 2) — see
 * [buildHttpClient]. The shims' internal host checks remain as belt-and-braces.
 */
object OpenAiCompatProvider {

    /**
     * Build an OpenAI-compatible LLM client for [baseUrl] + [chatCompletionsPath].
     *
     * ⚠️ Koog **REPLACES** the URL path with [chatCompletionsPath] — it does NOT
     * append it to [baseUrl]. (Confirmed by wire trace: a base of
     * `https://api.groq.com/openai` + the default path produced
     * `https://api.groq.com/v1/chat/completions`, dropping `/openai` → 404.)
     * Therefore [baseUrl] must be **host-only** and [chatCompletionsPath] must carry
     * the FULL path. All three providers follow this shape:
     *  - Groq:       base `https://api.groq.com`        path `/openai/v1/chat/completions`
     *  - OpenRouter: base `https://openrouter.ai`       path `/api/v1/chat/completions`
     *  - Gemini:     base `https://generativelanguage…` path `/v1beta/openai/chat/completions`
     *
     * Reusing this one OpenAI-wire client for Gemini avoids pulling Koog's native
     * google-client AAR (which carries a minSdk-35 floor + R8 factory risk).
     */
    fun client(
        apiKey: String,
        baseUrl: String,
        chatCompletionsPath: String,
        profile: ProviderProfile,
        generation: GenerationConfig = GenerationConfig(),
        contextConfig: AgentContextConfig = AgentContextConfig.DEFAULT,
    ): LLMClient {
        val base = OpenAILLMClient(
            apiKey,
            OpenAIClientSettings(baseUrl = baseUrl, chatCompletionsPath = chatCompletionsPath),
            KtorKoogHttpClient.Factory(buildHttpClient(profile, generation)),
        )
        // Wrap with Koog's retry decorator so transient 429s and 5xx back off instead of aborting
        // the run. The policy is DERIVED from [contextConfig] (F2) — it used to be a private
        // constant here whose KDoc said "tuned for FREE-TIER limits", while AgentContextConfig
        // carried a second, contradictory, entirely unused retry config. See [AgentRetryPolicy]
        // for the wall-clock bound that constant could not express, and for the measurement
        // showing hard client errors never entered this ladder in the first place.
        // Server-directed waits ("retry in Ns") come from [ServerRetryDelay] — Koog's default
        // extractor cannot read Gemini's wording, so this comment used to promise a wait that
        // never happened.
        return RetryingLLMClient(base, AgentRetryPolicy.configFor(contextConfig))
    }

    /**
     * Interceptor chain assembled from the [profile]'s declared wire facts (D1) —
     * add-time gating replaces per-shim hostname sniffing. The Gemini shims keep their
     * internal host checks as belt-and-braces, but which shims EXIST in the chain is now
     * decided here, per provider. Order matters: correctness shims first, then the
     * doctrine splice, then the tap (closest to the network) so traces record wire truth.
     */
    internal fun buildHttpClient(
        profile: ProviderProfile,
        generation: GenerationConfig,
        timeouts: AgentHttpTimeouts = AgentHttpTimeouts(),
    ): HttpClient = HttpClient(OkHttp) {
        // A stalled request must FAIL, not park the run. Without this the client had no
        // budget of any kind and a half-open socket hung the agent indefinitely — see
        // [AgentHttpTimeouts]. Installed on the Ktor layer so it applies whatever the
        // engine's own defaults happen to be.
        install(HttpTimeout) {
            requestTimeoutMillis = timeouts.requestMs
            connectTimeoutMillis = timeouts.connectMs
            socketTimeoutMillis = timeouts.socketMs
        }
        engine {
            // Belt-and-braces at the engine layer: OkHttp enforces these even for the
            // parts of the exchange Ktor's plugin does not wrap.
            config {
                connectTimeout(timeouts.connectMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                readTimeout(timeouts.socketMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                writeTimeout(timeouts.socketMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                callTimeout(timeouts.requestMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                // A dead pooled connection is the common cause of the stall; let OkHttp
                // retry it on a fresh socket rather than surface a spurious failure.
                retryOnConnectionFailure(true)
            }
            // ALWAYS-ON correctness fix — added first so the tap/debug below
            // see the corrected body.
            addInterceptor(KoogOpenAiToolArgRepair.interceptor())
            if (profile.requiresSignatureEcho) {
                // Gemini-direct: round-trips the per-tool-call
                // `extra_content.google.thought_signature` that Koog's OpenAI client drops.
                // Without it, Gemini 3.x 400s every multi-step (tool-using) turn. [SHIM #3]
                addInterceptor(GeminiThoughtSignatureRelay.interceptor())
            }
            if (profile.reasoningRequestStyle == ReasoningRequestStyle.GEMINI_THINKING_CONFIG) {
                // Gemini-direct: request thought summaries so the trace can show the model's
                // reasoning (extra_body.google.thinking_config). Additive; fails open. [SHIM #4]
                addInterceptor(GeminiThinkingConfig.interceptor())
            }
            if (profile.needsFinishReasonRepair) {
                // Gemini-direct crash fix: thought-only responses arrive with no
                // `finish_reason` on choices[0]; Koog's OpenAIChoice requires it and kills
                // the run. Injects "stop" so the turn deserializes. Added before the tap so
                // the trace still records the raw wire body. [SHIM #5]
                addInterceptor(GeminiFinishReasonRepair.interceptor())
            }
            if (profile.reasoningResponseStyle == ReasoningResponseStyle.REASONING_DETAILS) {
                // OpenRouter: reasoning models (Gemini 3, Claude) attach a message-level
                // `reasoning_details` array that MUST be echoed back unmodified on the
                // assistant message, or multi-step tool turns fail — OpenRouter's analogue
                // of the Gemini thought-signature relay. [SHIM #6]
                addInterceptor(OpenRouterReasoningDetailsEcho.interceptor())
            }
            if (profile.acceptsParallelToolCallsField) {
                // Force ONE tool call per turn. The weak model otherwise batches
                // perceive_screen + tap in a single assistant message, so the tap's
                // args are generated BEFORE perceive runs — it fills placeholders like
                // x="center_x" (schema-rejected → tool_use_failed → run dies, and the
                // perceive in the same batch is discarded too). With
                // parallel_tool_calls=false the model must perceive alone, see the real
                // numbers next turn, then tap with them. Cheap string-splice (no parse of
                // the ~500 KB image body); only touches tool-calling requests. Skipped for
                // endpoints whose profile says the field would be rejected.
                addInterceptor { chain ->
                    val req = chain.request()
                    val body = req.body
                    val ct = body?.contentType()
                    if (req.method != "POST" || ct?.subtype?.contains("json") != true) {
                        return@addInterceptor chain.proceed(req)
                    }
                    val text = runCatching { Buffer().also { body.writeTo(it) }.readUtf8() }.getOrNull()
                    if (text == null ||
                        "\"tools\"" !in text ||
                        "\"parallel_tool_calls\"" in text
                    ) {
                        return@addInterceptor chain.proceed(req)
                    }
                    val brace = text.indexOf('{')
                    if (brace < 0) return@addInterceptor chain.proceed(req)
                    val patched = text.substring(0, brace + 1) +
                        "\"parallel_tool_calls\":false," +
                        text.substring(brace + 1)
                    chain.proceed(req.newBuilder().method(req.method, patched.toRequestBody(ct)).build())
                }
            }
            if (!generation.isDefault) {
                // D2 piece 2: splice the user's thinking level + temperature onto the request,
                // mapped to this profile's reasoning dialect. Added last (before the tap) so a
                // user override is what the trace records. No-op skipped: a default config never
                // reaches here, so untouched setups keep a byte-identical request.
                addInterceptor(GenerationParams.interceptor(profile.reasoningRequestStyle, generation))
            }
            // NOTE: there is deliberately no anticipatory rate-limit pacing here. `TokenPacer`
            // used to sit at this point, holding a rolling per-host tokens-per-minute window and
            // sleeping before a call that would cross it. Removed 2026-09-22 for two measured
            // reasons. First, it could not make the agent faster: when a per-minute window is
            // full, pacing and a 429 both wait for the same window to refill, so the only thing
            // pacing saves is one rejected round trip — and a rejected request does not count
            // against the provider's quota either. Second, on the actual target tier (Gemini
            // free: 15 RPM, 250k TPM) the *request* limit binds before the token limit at our
            // prompt sizes, and the pacer only ever watched tokens. Claude Code carries no
            // anticipatory pacing at all; it retries patiently instead, which is what
            // [AgentRetryPolicy] + [ServerRetryDelay] now do.
            // Tap + debug interceptor. Reads only the BODY (never headers), so the
            // bearer API key is never captured. The tap feeds the on-device agent's
            // session logger ([AgentLlmTap]) when a run is active; debug logging is
            // unchanged. Response is read via peekBody so the real body is untouched.
            addInterceptor { chain ->
                val req = chain.request()
                val reqBody = req.body?.let { Buffer().also { buf -> it.writeTo(buf) }.readUtf8() } ?: ""
                if (BuildConfig.DEBUG) {
                    Log.i("AuraAgentHttp", "→ ${req.method} ${req.url} (body ${reqBody.length} chars)")
                    reqBody.chunked(3000).forEachIndexed { idx, c -> Log.i("AuraAgentHttp", "BODY[$idx] $c") }
                }
                val startedAt = System.currentTimeMillis()
                val resp = chain.proceed(req)
                if (AgentLlmTap.isActive) {
                    val respBody = runCatching { resp.peekBody(Long.MAX_VALUE).string() }.getOrDefault("")
                    AgentLlmTap.report(req.url.host, reqBody, respBody, System.currentTimeMillis() - startedAt)
                }
                resp
            }
        }
    }

    /**
     * Build an [LLModel] for an OpenAI-compatible provider from a user-selected model id.
     *
     * [visionCapable] is **tri-state** (P1). `ModelCatalog` computes this per model from the
     * provider's own `/models` listing and it is persisted at model-selection time; before this it
     * was computed, shown in the settings UI, and then **never consulted at runtime** — every model
     * was declared vision-capable and every turn sent image parts, whether or not the model could
     * read them.
     *
     * `null` means the provider published nothing (which includes every custom endpoint) and must
     * still declare vision: a false negative silently disables screen automation, which is a worse
     * outcome than one honest 400. Only an explicit `false` withholds the capability — and when it
     * does, the strategy must also stop re-injecting the screenshot, or the declaration and the
     * wire would disagree.
     *
     * [LLMCapability.OpenAIEndpoint.Completions] routes Koog's `determineParams`
     * to `/chat/completions` for a non-OpenAI model id (Groq/OpenRouter only speak
     * chat-completions).
     */
    fun modelFor(modelId: String, visionCapable: Boolean? = null): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = modelId,
        capabilities = buildList {
            add(LLMCapability.Completion)
            add(LLMCapability.Tools)
            if (visionCapable != false) add(LLMCapability.Vision.Image)
            add(LLMCapability.OpenAIEndpoint.Completions)
        },
    )
}
