package com.aura.aura_ui.agent.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device capability-detection test: runs the app's REAL [ModelCatalog.fetch] + [ModelCatalog.visionFor]
 * and [ProviderProfile.supportsReasoningControl] against the LIVE provider `/models` endpoints, on the
 * device (so the real `org.json` parser + OkHttp + TLS path exercise, not a JVM stub).
 *
 * Keys are injected as instrumentation runner arguments (never committed, never in BuildConfig):
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.groqKey=gsk_... \
 *   -Pandroid.testInstrumentationRunnerArguments.openrouterKey=sk-or-... \
 *   -Pandroid.testInstrumentationRunnerArguments.geminiKey=AIza... \
 *   -Pandroid.testInstrumentationRunnerArguments.nvidiaKey=nvapi-...
 * ```
 *
 * A provider whose key argument is absent is skipped ([assumeTrue]), so the suite degrades gracefully.
 *
 * Two capabilities are asserted, matching the app's two mechanisms:
 *  - **Vision / multimodal** — per model, from `ModelInfo.visionCapable`.
 *  - **Thinking / reasoning** — per provider, from `ProviderProfile.supportsReasoningControl()` (this gates
 *    whether the Brain screen shows the THINKING control at all — a pure-logic invariant, asserted always).
 */
@RunWith(AndroidJUnit4::class)
class ModelCatalogLiveTest {

    private val args = InstrumentationRegistry.getArguments()

    private fun keyArg(name: String): String? = args.getString(name)?.takeIf { it.isNotBlank() }

    private fun fetch(endpoint: LlmEndpoint, key: String): List<ModelInfo> =
        runBlocking { ModelCatalog.fetch(endpoint, key) }
            .getOrElse { throw AssertionError("live /models fetch failed for ${endpoint.id}: ${it.message}", it) }

    // ── Groq: Llama-4 vision heuristic; profile HIDES the thinking control ──────────────────────────
    @Test
    fun groq_detectsLlama4Vision_andHidesThinkingControl() {
        val key = keyArg("groqKey")
        assumeTrue("groqKey instrumentation arg not provided — skipping Groq", key != null)

        val endpoint = LlmEndpointCatalog.builtinById(LlmEndpointCatalog.GROQ_ID)!!
        val models = fetch(endpoint, key!!)

        assertTrue("Groq returned no models", models.isNotEmpty())
        val scout = models.firstOrNull { it.id.contains("scout", ignoreCase = true) }
        assertNotNull("Groq's Llama-4 Scout (the vision model) should be listed", scout)
        assertTrue("Llama-4 Scout must be detected vision-capable", scout!!.visionCapable)
        // A plain text model must NOT be flagged vision (guards the heuristic against over-matching).
        models.firstOrNull { it.id.contains("guard", ignoreCase = true) }
            ?.let { assertFalse("prompt-guard model must not be vision", it.visionCapable) }

        // Thinking is now gated PER-MODEL on Groq (the profile advertises reasoning for gpt-oss/qwen3),
        // so the Llama-4 vision default must be non-reasoning → its thinking control stays hidden.
        assertFalse("Llama-4 Scout must not be reasoning-capable", scout.reasoningCapable)
    }

    // ── OpenRouter: exact modality; mixed vision set; profile SHOWS the thinking control ────────────
    @Test
    fun openRouter_detectsMixedVision_andShowsThinkingControl() {
        val key = keyArg("openrouterKey")
        assumeTrue("openrouterKey instrumentation arg not provided — skipping OpenRouter", key != null)

        val endpoint = LlmEndpointCatalog.builtinById(LlmEndpointCatalog.OPENROUTER_ID)!!
        val models = fetch(endpoint, key!!)

        assertTrue("OpenRouter returned no models", models.isNotEmpty())
        val vision = models.count { it.visionCapable }
        // OpenRouter carries a large multimodal catalog AND text-only models — detection must be MIXED,
        // proving the exact input_modalities parse actually discriminates (not all-true / all-false).
        assertTrue("expected some vision models on OpenRouter, got 0 of ${models.size}", vision > 0)
        assertTrue("expected some non-vision models on OpenRouter (detection not discriminating)", vision < models.size)

        assertTrue(
            "OpenRouter profile must SHOW the thinking control (unified reasoning param)",
            endpoint.profile.supportsReasoningControl(),
        )
    }

    // ── Gemini: generation>=1.5 heuristic; embeddings excluded; profile SHOWS the thinking control ──
    @Test
    fun gemini_detectsGenerationVision_excludesEmbeddings_andShowsThinkingControl() {
        val key = keyArg("geminiKey")
        assumeTrue("geminiKey instrumentation arg not provided — skipping Gemini", key != null)

        val endpoint = LlmEndpointCatalog.builtinById(LlmEndpointCatalog.GEMINI_ID)!!
        val models = fetch(endpoint, key!!)

        assertTrue("Gemini returned no models", models.isNotEmpty())
        val flash = models.firstOrNull { it.id.contains("gemini-2", ignoreCase = true) && !it.id.contains("embedding") }
        assertNotNull("a Gemini 2.x generative model should be listed", flash)
        assertTrue("Gemini 2.x must be detected vision-capable", flash!!.visionCapable)
        // Embedding models are utility, not multimodal — must be excluded.
        models.firstOrNull { it.id.contains("embedding", ignoreCase = true) }
            ?.let { assertFalse("Gemini embedding model must not be vision", it.visionCapable) }

        assertTrue(
            "Gemini profile must SHOW the thinking control (thinking_config)",
            endpoint.profile.supportsReasoningControl(),
        )
    }

    // ── NVIDIA NIM: not a built-in — a custom OpenAI-compatible endpoint (GENERIC vision, no thinking) ─
    @Test
    fun nvidiaNim_asCustomEndpoint_detectsVlVision_andHidesThinkingControl() {
        val key = keyArg("nvidiaKey")
        assumeTrue("nvidiaKey instrumentation arg not provided — skipping NVIDIA NIM", key != null)

        // Mirrors exactly what CustomEndpointEditor builds for a user-added OpenAI-compatible endpoint.
        val endpoint = LlmEndpoint(
            id = "custom:nvidia-nim",
            displayName = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com",
            chatCompletionsPath = "/v1/chat/completions",
            modelsUrl = "https://integrate.api.nvidia.com/v1/models",
            authScheme = AuthScheme.BEARER,
            modelListStyle = ModelListStyle.OPENAI_DATA,
            visionInference = VisionInference.GENERIC,
            profile = ProviderProfile(),
            isBuiltIn = false,
            requiresKey = true,
        )
        val models = fetch(endpoint, key!!)

        assertTrue("NVIDIA NIM returned no models", models.isNotEmpty())
        // The GENERIC heuristic keys off id markers ("-vl", "vision", …). NVIDIA's catalog carries VLMs.
        val vlm = models.firstOrNull {
            (it.id.contains("-vl", ignoreCase = true) || it.id.contains("vision", ignoreCase = true)) &&
                !it.id.contains("embed", ignoreCase = true)
        }
        assertNotNull("a NVIDIA vision-language model should be listed and detected", vlm)
        assertTrue("NVIDIA VLM must be detected vision-capable by GENERIC rule", vlm!!.visionCapable)

        assertFalse(
            "A custom endpoint must HIDE the thinking control (ProviderProfile() default)",
            endpoint.profile.supportsReasoningControl(),
        )
    }

    // ── Per-model reasoning + free detection on live JSON (org.json is real on-device) ─────────

    @Test
    fun groq_detectsReasoningModelsAndNotLlama4() {
        val key = keyArg("groqKey")
        assumeTrue("groqKey not provided — skipping", key != null)
        val models = fetch(LlmEndpointCatalog.builtinById(LlmEndpointCatalog.GROQ_ID)!!, key!!)
        val oss = models.firstOrNull { it.id.contains("gpt-oss", ignoreCase = true) }
        assertNotNull("Groq should list a gpt-oss reasoning model", oss)
        assertTrue("gpt-oss must be reasoning-capable", oss!!.reasoningCapable)
        assertFalse("gpt-oss always reasons — cannot be turned off", oss.canDisableThinking)
        models.firstOrNull { it.id.contains("scout", ignoreCase = true) }
            ?.let { assertFalse("Llama-4 Scout must NOT be reasoning-capable", it.reasoningCapable) }
    }

    @Test
    fun openRouter_detectsExactReasoningAndFree() {
        val key = keyArg("openrouterKey")
        assumeTrue("openrouterKey not provided — skipping", key != null)
        val models = fetch(LlmEndpointCatalog.builtinById(LlmEndpointCatalog.OPENROUTER_ID)!!, key!!)
        val reasoning = models.count { it.reasoningCapable }
        assertTrue("OpenRouter should expose reasoning models via supported_parameters", reasoning > 0)
        assertTrue("reasoning detection must be MIXED (not all models)", reasoning < models.size)
        assertTrue("OpenRouter reasoning models can be disabled (enabled:false)", models.filter { it.reasoningCapable }.all { it.canDisableThinking })
        assertTrue("OpenRouter should report at least one free model", models.any { it.isFree == true })
        assertTrue("OpenRouter should report at least one paid model", models.any { it.isFree == false })
    }

    @Test
    fun gemini_detectsReasoningAndDisableByFamily() {
        val key = keyArg("geminiKey")
        assumeTrue("geminiKey not provided — skipping", key != null)
        val models = fetch(LlmEndpointCatalog.builtinById(LlmEndpointCatalog.GEMINI_ID)!!, key!!)
        val flash = models.firstOrNull { it.id.equals("gemini-2.5-flash", ignoreCase = true) }
        assertNotNull("gemini-2.5-flash should be listed", flash)
        assertTrue("gemini-2.5-flash must be reasoning-capable", flash!!.reasoningCapable)
        assertTrue("flash family can disable thinking (budget 0)", flash.canDisableThinking)
        models.firstOrNull { it.id.equals("gemini-2.5-pro", ignoreCase = true) }
            ?.let { assertFalse("gemini-2.5-pro cannot fully disable thinking", it.canDisableThinking) }
        // Pricing is unknown for Gemini — the free filter stays hidden there.
        assertTrue("Gemini must report unknown pricing (isFree null)", models.all { it.isFree == null })
    }

    @Test
    fun nvidiaNim_customEndpoint_hasNoReasoningCapability() {
        val key = keyArg("nvidiaKey")
        assumeTrue("nvidiaKey not provided — skipping", key != null)
        val endpoint = LlmEndpoint(
            id = "custom:nvidia-nim",
            displayName = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com",
            chatCompletionsPath = "/v1/chat/completions",
            modelsUrl = "https://integrate.api.nvidia.com/v1/models",
            authScheme = AuthScheme.BEARER,
            modelListStyle = ModelListStyle.OPENAI_DATA,
            visionInference = VisionInference.GENERIC,
            profile = ProviderProfile(),
            isBuiltIn = false,
            requiresKey = true,
        )
        val models = fetch(endpoint, key!!)
        assertTrue("a custom endpoint reports no reasoning capability (ReasoningInference.NONE)", models.none { it.reasoningCapable })
        assertTrue("NVIDIA reports no pricing — free stays unknown", models.all { it.isFree == null })
    }
}
