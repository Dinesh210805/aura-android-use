package com.aura.aura_ui.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One model exposed by a provider, with the single fact the agent cares about:
 * whether it can read the SoM-annotated screenshots ([visionCapable]). A
 * non-vision model cannot drive screen automation, so the settings UI must steer
 * the user toward vision models.
 */
data class ModelInfo(
    val id: String,
    val visionCapable: Boolean,
    val contextWindow: Int? = null,
    val ownedBy: String? = null,
    /** Whether this model can *think* (reason). Gates the THINKING control per-model. */
    val reasoningCapable: Boolean = false,
    /** Whether thinking can be turned fully OFF for this model. Meaningful only when
     * [reasoningCapable]. Always-reasoning models (OpenAI o-series, Groq gpt-oss) are false, so the
     * UI hides the "Off" option rather than offering a button that the wire can't honor. */
    val canDisableThinking: Boolean = false,
    /** True/false when the provider reports pricing (OpenRouter), else null (unknown). Drives the
     * "Free only" filter. */
    val isFree: Boolean? = null,
)

/**
 * Fetches the live model list from a provider's `/models` endpoint.
 *
 * Vision flagging is **provider-specific by necessity**: Groq's `/models` carries
 * no modality field (verified against the live endpoint), so vision is inferred
 * from the model id (Llama 4 family). OpenRouter and Gemini do expose modality, so
 * their flags are exact. Errors surface as a failed [Result] (never silent) so the
 * caller can show a retry affordance.
 *
 * Slice 1 is validated end-to-end on **Groq**. OpenRouter/Gemini parsing follows
 * the documented response shapes but is verified in slice 2.
 */
object ModelCatalog {

    /** Mandatory on Anthropic's native endpoints; see [AuthScheme.ANTHROPIC_X_API_KEY]. */
    internal const val ANTHROPIC_VERSION = "2023-06-01"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(endpoint: LlmEndpoint, apiKey: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(endpoint.modelsUrl).get()
                authHeaders(endpoint.authScheme, apiKey).forEach { (name, value) -> builder.header(name, value) }
                try {
                    http.newCall(builder.build()).execute().use { resp ->
                        // Map the status to a friendly, typed error — never leak the raw provider body.
                        if (!resp.isSuccessful) throw httpError(resp.code)
                        val body = resp.body?.string().orEmpty()
                        parse(endpoint, body)
                            // Vision-capable models first (the only ones that can
                            // drive automation), then alphabetically by id.
                            .sortedWith(compareByDescending<ModelInfo> { it.visionCapable }.thenBy { it.id })
                    }
                } catch (e: ModelFetchError) {
                    throw e
                } catch (e: IOException) {
                    // DNS failure, timeout, connection reset — all network-class, one honest message.
                    throw ModelFetchError.Network
                }
            }
        }

    /**
     * Friendly, typed fetch failures. The settings UI renders [userMessage] directly, so the user sees
     * "key rejected" vs "rate limited" vs "network" instead of an opaque `HTTP 401: {…body…}` — and the
     * provider's raw error body is never surfaced.
     */
    sealed class ModelFetchError(val userMessage: String) : Exception(userMessage) {
        /** 401/403 — the /models endpoint is auth-gated, so this doubles as a free key check. */
        object BadKey : ModelFetchError("Key rejected — check the API key for this provider.")
        object RateLimited : ModelFetchError("Rate limited — wait a moment, then try again.")
        object Network : ModelFetchError("Network problem — check your connection and retry.")
        class Http(val code: Int) : ModelFetchError("The provider returned an error (HTTP $code).")
    }

    private fun httpError(code: Int): ModelFetchError = when (code) {
        401, 403 -> ModelFetchError.BadKey
        429 -> ModelFetchError.RateLimited
        else -> ModelFetchError.Http(code)
    }

    internal fun authHeaders(scheme: AuthScheme, key: String): Map<String, String> = when (scheme) {
        AuthScheme.BEARER -> mapOf("Authorization" to "Bearer $key")
        AuthScheme.X_GOOG_API_KEY -> mapOf("x-goog-api-key" to key)
        // anthropic-version is mandatory on every native Anthropic endpoint, not optional — the
        // request is rejected without it. Pinned rather than "latest" so a future default cannot
        // change the response shape under us.
        AuthScheme.ANTHROPIC_X_API_KEY ->
            mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION)
        AuthScheme.NONE -> emptyMap()
    }

    private fun parse(endpoint: LlmEndpoint, body: String): List<ModelInfo> =
        when (endpoint.modelListStyle) {
            ModelListStyle.OPENAI_DATA -> parseOpenAiShape(endpoint, body)
            ModelListStyle.GEMINI_MODELS -> parseGeminiShape(endpoint, body)
        }

    /** OpenAI-shape providers: `{ "data": [ { "id": ... } ] }`. */
    internal fun parseOpenAiShape(endpoint: LlmEndpoint, body: String): List<ModelInfo> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val m = data.getJSONObject(i)
            val id = m.getString("id")
            ModelInfo(
                id = id,
                visionCapable = visionFor(endpoint, id, m),
                // `max_input_tokens` is Anthropic's spelling of the same fact. Without the fallback
                // every Claude model reported a null window, which is not inert: it silently drops
                // the run back to the fixed 12k compaction threshold (AgentContextConfig), so the
                // agent forgets history it had already paid for.
                contextWindow = m.optInt("context_window").takeIf { it > 0 }
                    ?: m.optInt("max_input_tokens").takeIf { it > 0 },
                ownedBy = m.optString("owned_by").takeIf { it.isNotBlank() },
                reasoningCapable = reasoningFor(endpoint, id, m),
                canDisableThinking = canDisableFor(endpoint, id),
                isFree = freeFor(id, m),
            )
        }
    }

    /** Gemini: `{ "models": [ { "name": "models/gemini-…", ... } ] }`. */
    private fun parseGeminiShape(endpoint: LlmEndpoint, body: String): List<ModelInfo> {
        val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).mapNotNull { i ->
            val m = models.getJSONObject(i)
            val id = m.optString("name").removePrefix("models/").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ModelInfo(
                id = id,
                visionCapable = visionFor(endpoint, id, m),
                contextWindow = m.optInt("inputTokenLimit").takeIf { it > 0 },
                reasoningCapable = reasoningFor(endpoint, id, m),
                canDisableThinking = canDisableFor(endpoint, id),
                isFree = freeFor(id, m),
            )
        }
    }

    // ── Vision detection (per-endpoint) ────────────────────────────────────────

    /**
     * Vision decision routed by the endpoint's declared rule. [modelJson] carries modality when the
     * provider reports it (only OpenRouter does); the id-based rules ignore it.
     */
    internal fun visionFor(endpoint: LlmEndpoint, id: String, modelJson: JSONObject?): Boolean =
        when (endpoint.visionInference) {
            VisionInference.LLAMA4_FAMILY -> llamaVision(id)
            VisionInference.OPENROUTER_MODALITY -> modelJson?.let(::openRouterVision) ?: false
            VisionInference.GEMINI_GENERATION -> geminiVision(id)
            VisionInference.OPENAI_FAMILY -> openAiVision(id)
            VisionInference.ALL_MODELS -> true
            VisionInference.GENERIC -> genericVision(id)
        }

    /** Groq exposes no modality field — the Llama 4 family is the multimodal set. */
    private fun llamaVision(id: String): Boolean {
        val l = id.lowercase()
        return "llama-4" in l || "scout" in l || "maverick" in l || "vision" in l
    }

    /** OpenAI: `/models` carries no modality — the 4o/4.1/5 + o-series families are multimodal. */
    internal fun openAiVision(id: String): Boolean {
        val l = id.lowercase()
        if ("embedding" in l || "whisper" in l || "tts" in l || "dall-e" in l) return false
        if ("gpt-3.5" in l) return false
        return "gpt-4o" in l || "gpt-4.1" in l || "gpt-5" in l ||
            "o1" in l || "o3" in l || "o4" in l
    }

    /**
     * Custom endpoints and the OpenAI-wire presets that publish no modality field — flag common
     * vision markers, else warn (safer for automation).
     *
     * `pixtral` (Mistral) and `glm-4.5v` (Z.ai) are named because neither carries any of the other
     * markers, and a wrong *negative* here is the expensive direction: it withholds the vision
     * capability, so the agent drops to the text-only `read_screen` path on a model that can
     * actually see. Qwen-VL already matches via `-vl`.
     */
    private fun genericVision(id: String): Boolean {
        val l = id.lowercase()
        return "vision" in l || "llava" in l || "-vl" in l || "-vl-" in l || "vl-" in l ||
            "4o" in l || "gemini" in l || "claude" in l ||
            "pixtral" in l || "glm-4.5v" in l
    }

    /** OpenRouter reports modality under `architecture` — exact when present. */
    private fun openRouterVision(model: JSONObject): Boolean {
        val arch = model.optJSONObject("architecture") ?: return false
        arch.optJSONArray("input_modalities")?.let { mods ->
            for (i in 0 until mods.length()) if (mods.optString(i).equals("image", true)) return true
        }
        return arch.optString("modality").contains("image", ignoreCase = true)
    }

    /**
     * Every Gemini generation since 1.5 is multimodal, so match on the parsed
     * generation number (forward-compatible with 3.x, 4.x, …) instead of a
     * hardcoded family list; exclude embedding/aqa utility models.
     */
    internal fun geminiVision(id: String): Boolean {
        val lower = id.lowercase()
        // Utility models are not screen-reading multimodal: embeddings, attributed-QA, speech (TTS),
        // and image-*generation* models. Excluding them keeps the "vision-only" list honest.
        if ("embedding" in lower || "aqa" in lower || "tts" in lower || "image" in lower) return false
        if ("gemini-exp" in lower) return true
        val generation = GEMINI_GENERATION.find(lower)?.groupValues?.get(1)?.toDoubleOrNull()
        return generation != null && generation >= 1.5
    }

    private val GEMINI_GENERATION = Regex("""\bgemini-(\d+(?:\.\d+)?)""")

    // ── Reasoning detection (per-endpoint) ─────────────────────────────────────

    /**
     * Whether [id] can *think*. [modelJson] carries `supported_parameters` when the provider reports
     * it (OpenRouter only); the id-based rules ignore it. When json is absent (the runtime wire-gate
     * calls this id-only), OpenRouter defaults to `true` — it normalizes/ignores a reasoning field on
     * a non-reasoning model, so over-allowing there is safe; the 400-prone providers use exact id rules.
     */
    internal fun reasoningFor(endpoint: LlmEndpoint, id: String, modelJson: JSONObject?): Boolean =
        when (endpoint.reasoningInference) {
            ReasoningInference.NONE -> false
            ReasoningInference.OPENROUTER_SUPPORTED_PARAMS ->
                modelJson?.optJSONArray("supported_parameters")?.let { arr ->
                    (0 until arr.length()).any { arr.optString(it).equals("reasoning", ignoreCase = true) }
                } ?: true
            ReasoningInference.OPENAI_OSERIES -> openAiReasoning(id)
            ReasoningInference.GROQ_FAMILY -> groqReasoning(id)
            ReasoningInference.GEMINI_GENERATION -> geminiReasoning(id)
        }

    /** Whether thinking can be turned fully OFF for [id]. Only consulted when reasoning-capable. */
    internal fun canDisableFor(endpoint: LlmEndpoint, id: String): Boolean =
        when (endpoint.reasoningInference) {
            // OpenRouter honors `reasoning:{enabled:false}` for every reasoning model.
            ReasoningInference.OPENROUTER_SUPPORTED_PARAMS -> true
            // Gemini: only the 2.5-era flash/lite family can drop to thinking_budget 0. Pro can't, and
            // Gemini 3.x uses thinking_level which has no true "off".
            ReasoningInference.GEMINI_GENERATION -> {
                val l = id.lowercase()
                val gen = GEMINI_GENERATION.find(l)?.groupValues?.get(1)?.toDoubleOrNull()
                gen != null && gen < 3.0 && ("flash" in l || "lite" in l)
            }
            // OpenAI o-series / Groq gpt-oss+qwen3 always reason — no true off (chat API has none).
            ReasoningInference.OPENAI_OSERIES, ReasoningInference.GROQ_FAMILY -> false
            ReasoningInference.NONE -> false
        }

    /** Groq's reasoning models (verified on the live catalog): gpt-oss + qwen3 families. */
    internal fun groqReasoning(id: String): Boolean {
        val l = id.lowercase()
        return "gpt-oss" in l || "qwen3" in l || "qwq" in l || "deepseek-r1" in l
    }

    /** OpenAI reasoning models: the o-series (o1/o3/o4…) and GPT-5 family. */
    internal fun openAiReasoning(id: String): Boolean {
        val l = id.lowercase()
        if ("gpt-5" in l) return true
        return OPENAI_O_SERIES.containsMatchIn(l)
    }

    /** Gemini thinking models: generation >= 2.5 (2.0/1.5 don't think), plus exp; utility excluded. */
    internal fun geminiReasoning(id: String): Boolean {
        val l = id.lowercase()
        if ("embedding" in l || "aqa" in l || "tts" in l || "image" in l) return false
        if ("gemini-exp" in l) return true
        val generation = GEMINI_GENERATION.find(l)?.groupValues?.get(1)?.toDoubleOrNull()
        return generation != null && generation >= 2.5
    }

    private val OPENAI_O_SERIES = Regex("""(^|[/\-])o[1-4]($|[\-/])""")

    // ── Free-tier detection (provider-agnostic; only OpenRouter reports pricing) ────────────────

    /**
     * True/false when pricing is known (OpenRouter reports a `pricing` object, or the id ends `:free`),
     * else null (unknown — every other provider). Powers the "Free only" filter.
     */
    internal fun freeFor(id: String, modelJson: JSONObject?): Boolean? {
        if (id.endsWith(":free", ignoreCase = true)) return true
        val pricing = modelJson?.optJSONObject("pricing") ?: return null
        val prompt = pricing.optString("prompt").toDoubleOrNull()
        val completion = pricing.optString("completion").toDoubleOrNull()
        return if (prompt != null && completion != null) prompt == 0.0 && completion == 0.0 else null
    }

    /**
     * Pure, id-only reasoning check used by the runtime wire-gate ([ProviderRegistry.clientFor]) to
     * decide whether a user-set reasoning level may reach the selected model. Delegates to
     * [reasoningFor] with no json, so the 400-prone providers (Groq/OpenAI/Gemini) use exact id rules
     * while OpenRouter/custom default to allow (safe: OpenRouter normalizes; custom profile is NONE).
     */
    fun reasoningCapableForWire(endpoint: LlmEndpoint, modelId: String): Boolean =
        reasoningFor(endpoint, modelId, null)
}
