package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog facts ARE the provider spec. The id-stability tests are load-bearing for
 * MIGRATION: ProviderKeyStore keys by endpoint id, and the built-in ids must equal the old
 * enum's lowercased names so existing users' saved keys (`provider_key_groq`, …) still resolve.
 */
class LlmEndpointCatalogTest {

    @Test
    fun `built-in ids are the legacy lowercased enum names — migration contract`() {
        assertEquals("groq", LlmEndpointCatalog.GROQ_ID)
        assertEquals("openrouter", LlmEndpointCatalog.OPENROUTER_ID)
        assertEquals("gemini", LlmEndpointCatalog.GEMINI_ID)
    }

    @Test
    fun `catalog ships exactly the preset set`() {
        assertEquals(
            listOf(
                "groq", "openrouter", "gemini", "openai", "anthropic",
                "mistral", "dashscope", "cerebras", "zai", "xai", "together", "moonshot", "deepseek",
            ),
            LlmEndpointCatalog.BUILTINS.map { it.id },
        )
        assertTrue("all built-ins flagged built-in", LlmEndpointCatalog.BUILTINS.all { it.isBuiltIn })
    }

    /**
     * The first five ids are the legacy lowercased enum names and `ProviderKeyStore` keys off them,
     * so a user upgrading keeps their saved key. Appending presets must never reorder or rename
     * them — this pins the prefix independently of the full-list assertion above, so a future
     * addition that also disturbs the prefix fails with a message that says which rule it broke.
     */
    @Test
    fun `adding presets never disturbs the migrating ids`() {
        assertEquals(
            listOf("groq", "openrouter", "gemini", "openai", "anthropic"),
            LlmEndpointCatalog.BUILTINS.map { it.id }.take(5),
        )
    }

    /**
     * Every preset added 2026-09-22 is plain OpenAI wire. A shim silently attaching itself to one
     * of them would mean a request we never verified against that provider — and `ProviderProfile`
     * defaults are what decide which interceptors exist in the chain.
     */
    @Test
    fun `the new presets declare no wire shims`() {
        val added = setOf("mistral", "dashscope", "cerebras", "zai", "xai", "together", "moonshot", "deepseek")
        LlmEndpointCatalog.BUILTINS.filter { it.id in added }.forEach { e ->
            assertEquals("${e.id} should be plain OpenAI wire", ProviderProfile(), e.profile)
            assertEquals("${e.id} lists models in the OpenAI shape", ModelListStyle.OPENAI_DATA, e.modelListStyle)
            assertEquals("${e.id} authenticates with Bearer", AuthScheme.BEARER, e.authScheme)
        }
    }

    /**
     * Blank on purpose. A stale default model id fails at RUNTIME with a 400 rather than at build
     * time; blank makes the picker prompt instead. The pre-D2 code shipped a single global default
     * holding a *Groq* model id, so configuring any other provider and not choosing a model sent
     * `meta-llama/llama-4-scout-…` to it — a guaranteed 400.
     */
    @Test
    fun `presets added without a verified default model leave it blank`() {
        val added = setOf("mistral", "dashscope", "cerebras", "zai", "xai", "together", "moonshot", "deepseek")
        LlmEndpointCatalog.BUILTINS.filter { it.id in added }.forEach {
            assertEquals("${it.id} must not guess a default model", "", it.defaultModelId)
        }
    }

    /**
     * Z.ai does not serve `/v1/...` — that path 404s, while `/api/paas/v4/...` answers 401 (probed
     * live 2026-09-22). This is the one preset whose path is not the OpenAI default, so it is the
     * one a "tidy-up" would most plausibly break.
     */
    @Test
    fun `zai keeps its non-standard paas path`() {
        val zai = LlmEndpointCatalog.builtinById("zai")!!
        assertEquals("/api/paas/v4/chat/completions", zai.chatCompletionsPath)
        assertEquals("https://api.z.ai/api/paas/v4/models", zai.modelsUrl)
    }

    /** DeepSeek serves `/models` at the root, not under `/v1`. */
    @Test
    fun `deepseek lists models at the root`() {
        assertEquals("https://api.deepseek.com/models", LlmEndpointCatalog.builtinById("deepseek")!!.modelsUrl)
    }

    /**
     * The regression this session found: Anthropic's `/v1/models` is a NATIVE endpoint that ignores
     * `Authorization` and demands `x-api-key`. Declared BEARER, the model picker could never
     * authenticate. The chat path is unaffected — it rides the OpenAI-compat layer, which is why
     * chat worked while the picker stayed empty and nobody noticed.
     */
    @Test
    fun `anthropic authenticates the model list the way its native API requires`() {
        val a = LlmEndpointCatalog.builtinById("anthropic")!!
        assertEquals(AuthScheme.ANTHROPIC_X_API_KEY, a.authScheme)
        assertEquals("/v1/chat/completions", a.chatCompletionsPath)
    }

    @Test
    fun `groq endpoint reproduces the verified wire shape`() {
        val groq = LlmEndpointCatalog.builtinById("groq")!!
        assertEquals("https://api.groq.com", groq.baseUrl)
        assertEquals("/openai/v1/chat/completions", groq.chatCompletionsPath)
        assertEquals("https://api.groq.com/openai/v1/models", groq.modelsUrl)
        assertEquals(AuthScheme.BEARER, groq.authScheme)
        assertEquals(ModelListStyle.OPENAI_DATA, groq.modelListStyle)
        assertEquals(VisionInference.LLAMA4_FAMILY, groq.visionInference)
    }

    @Test
    fun `gemini uses google model-list shape and x-goog auth for the models fetch`() {
        val g = LlmEndpointCatalog.builtinById("gemini")!!
        assertEquals(AuthScheme.X_GOOG_API_KEY, g.authScheme)
        assertEquals(ModelListStyle.GEMINI_MODELS, g.modelListStyle)
        assertTrue("gemini profile keeps the D1 shims", g.profile.requiresSignatureEcho)
    }

    @Test
    fun `builtinById is null for an unknown id`() {
        assertNotNull(LlmEndpointCatalog.builtinById("openai"))
        assertNull(LlmEndpointCatalog.builtinById("nope"))
    }

    @Test
    fun `gemini endpoint id is the constant the companion reads for its BYOK key`() {
        assertEquals("gemini", LlmEndpointCatalog.GEMINI_ID)
        assertEquals(LlmEndpointCatalog.GEMINI_ID, LlmEndpointCatalog.builtinById("gemini")!!.id)
    }

    // ── Per-endpoint default models ────────────────────────────────────────────
    // The fallback used to be one global constant holding a Groq model id, so a user who
    // configured Gemini and never opened the model picker had `meta-llama/llama-4-scout-…`
    // posted to Google. These lock the shape of the fix, not just the current values.

    @Test
    fun `gemini defaults to the verified flash-lite model`() {
        // Verified present on 2026-07-30 by enumerating a live key, not from memory.
        assertEquals("gemini-3.5-flash-lite", LlmEndpointCatalog.GEMINI_DEFAULT_MODEL_ID)
        assertEquals(
            LlmEndpointCatalog.GEMINI_DEFAULT_MODEL_ID,
            LlmEndpointCatalog.builtinById("gemini")!!.defaultModelId,
        )
    }

    @Test
    fun `no built-in endpoint defaults to another provider's model id`() {
        // The regression that mattered: a Groq id leaking into Gemini/Anthropic/OpenAI.
        val groqDefault = LlmEndpointCatalog.builtinById("groq")!!.defaultModelId
        LlmEndpointCatalog.BUILTINS
            .filter { it.id != "groq" && it.defaultModelId.isNotBlank() }
            .forEach { endpoint ->
                assertNotEquals(
                    "${endpoint.id} must not default to Groq's model id",
                    groqDefault,
                    endpoint.defaultModelId,
                )
            }
    }

    @Test
    fun `groq still defaults to the app-wide model so existing installs are unchanged`() {
        assertEquals(
            LlmEndpointCatalog.DEFAULT_MODEL_ID,
            LlmEndpointCatalog.builtinById("groq")!!.defaultModelId,
        )
    }

    @Test
    fun `a custom endpoint with no default deserializes to blank rather than a wrong model`() {
        // Blank means "no default" so callers prompt, instead of inventing an id that 400s.
        val custom = LlmEndpointCatalog.builtinById("groq")!!.copy(id = "custom", defaultModelId = "")
        assertEquals("", custom.defaultModelId)
    }
}
