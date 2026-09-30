package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 — Gemini vision flagging. Gemini 3.x (and future generations) must be
 * flagged vision-capable; embedding/aqa utility models must never be.
 */
class ModelCatalogTest {

    // ── 2026-09-22: Anthropic's model list, and the presets added beside it ───

    /**
     * Anthropic's `/v1/models` is a NATIVE endpoint, not part of their OpenAI-compat layer: it
     * ignores `Authorization` and requires `x-api-key` plus the mandatory `anthropic-version`.
     * Verified live — a Bearer token returns `"invalid x-api-key"`. Declared BEARER until now, so
     * the Anthropic model picker could never authenticate.
     */
    @Test
    fun `anthropic model list sends x-api-key and the mandatory version header`() {
        val headers = ModelCatalog.authHeaders(AuthScheme.ANTHROPIC_X_API_KEY, "sk-ant-abc")
        assertEquals("sk-ant-abc", headers["x-api-key"])
        assertEquals(ModelCatalog.ANTHROPIC_VERSION, headers["anthropic-version"])
        assertFalse("Authorization is ignored by this endpoint", headers.containsKey("Authorization"))
    }

    /** The other schemes must be untouched by that addition. */
    @Test
    fun `bearer and x-goog schemes are unchanged`() {
        assertEquals("Bearer k", ModelCatalog.authHeaders(AuthScheme.BEARER, "k")["Authorization"])
        assertEquals("k", ModelCatalog.authHeaders(AuthScheme.X_GOOG_API_KEY, "k")["x-goog-api-key"])
        assertTrue(ModelCatalog.authHeaders(AuthScheme.NONE, "k").isEmpty())
    }

    /**
     * Anthropic spells the context window `max_input_tokens`. Reading only `context_window` left it
     * null on every Claude model — not inert, because null silently drops the run back to the fixed
     * 12k compaction threshold, making the agent forget history it had already paid for.
     */
    @Test
    fun `max_input_tokens is read when context_window is absent`() {
        val anthropic = LlmEndpointCatalog.builtinById("anthropic")!!
        val body = """{"data":[{"id":"claude-opus-5","max_input_tokens":200000}]}"""
        assertEquals(200_000, ModelCatalog.parseOpenAiShape(anthropic, body).single().contextWindow)
    }

    /** `context_window` still wins where a provider sends it — this is a fallback, not a swap. */
    @Test
    fun `context_window still takes precedence`() {
        val groq = LlmEndpointCatalog.builtinById("groq")!!
        val body = """{"data":[{"id":"m","context_window":131072,"max_input_tokens":9}]}"""
        assertEquals(131_072, ModelCatalog.parseOpenAiShape(groq, body).single().contextWindow)
    }

    /** Neither field present stays null — "unknown", which the config treats as its own case. */
    @Test
    fun `an unreported window stays null rather than zero`() {
        val groq = LlmEndpointCatalog.builtinById("groq")!!
        assertNull(ModelCatalog.parseOpenAiShape(groq, """{"data":[{"id":"m"}]}""").single().contextWindow)
    }

    /**
     * Pixtral and GLM-4.5V carry none of the generic vision markers, and a wrong NEGATIVE is the
     * expensive direction: it withholds the vision capability, dropping the agent to the text-only
     * `read_screen` path on a model that can actually see the screen.
     */
    @Test
    fun `vision markers cover the new presets' multimodal models`() {
        val mistral = LlmEndpointCatalog.builtinById("mistral")!!
        val zai = LlmEndpointCatalog.builtinById("zai")!!
        val qwen = LlmEndpointCatalog.builtinById("dashscope")!!
        assertTrue(ModelCatalog.visionFor(mistral, "pixtral-large-latest", null))
        assertTrue(ModelCatalog.visionFor(zai, "glm-4.5v", null))
        assertTrue(ModelCatalog.visionFor(qwen, "qwen-vl-max", null))
        // A text-only model must not be flagged — that direction costs one honest 400, not silence.
        assertFalse(ModelCatalog.visionFor(mistral, "mistral-small-latest", null))
    }

    @Test
    fun `gemini 3 models are vision capable`() {
        assertTrue(ModelCatalog.geminiVision("gemini-3-flash-preview"))
        assertTrue(ModelCatalog.geminiVision("gemini-3.1-pro"))
    }

    @Test
    fun `future gemini generations are vision capable`() {
        assertTrue(ModelCatalog.geminiVision("gemini-4-flash"))
    }

    @Test
    fun `existing gemini families keep their vision flag`() {
        assertTrue(ModelCatalog.geminiVision("gemini-1.5-pro"))
        assertTrue(ModelCatalog.geminiVision("gemini-2.5-flash"))
        assertTrue(ModelCatalog.geminiVision("gemini-exp-1206"))
    }

    @Test
    fun `embedding and aqa utility models are never vision capable`() {
        assertFalse(ModelCatalog.geminiVision("gemini-embedding-001"))
        assertFalse(ModelCatalog.geminiVision("text-embedding-004"))
        assertFalse(ModelCatalog.geminiVision("aqa"))
    }

    @Test
    fun `non-gemini and legacy gemini 1_0 models are not vision capable`() {
        assertFalse(ModelCatalog.geminiVision("gemma-3-27b-it"))
        assertFalse(ModelCatalog.geminiVision("gemini-1.0-pro"))
    }

    @Test
    fun `openAiVision flags 4o and o-series, not text-only ids`() {
        assertTrue(ModelCatalog.openAiVision("gpt-4o"))
        assertTrue(ModelCatalog.openAiVision("gpt-4o-mini"))
        assertTrue(ModelCatalog.openAiVision("gpt-4.1"))
        assertTrue(ModelCatalog.openAiVision("o3"))
        assertFalse(ModelCatalog.openAiVision("gpt-3.5-turbo"))
        assertFalse(ModelCatalog.openAiVision("text-embedding-3-large"))
    }

    @Test
    fun `ALL_MODELS vision rule always flags true`() {
        val anthropic = LlmEndpointCatalog.builtinById("anthropic")!!
        assertTrue(ModelCatalog.visionFor(anthropic, "claude-sonnet-4", null))
        assertTrue(ModelCatalog.visionFor(anthropic, "anything", null))
    }

    @Test
    fun `GENERIC vision rule flags known markers only`() {
        val custom = LlmEndpointCatalog.builtinById("groq")!!.copy(
            id = "custom:x", isBuiltIn = false, visionInference = VisionInference.GENERIC,
        )
        assertTrue(ModelCatalog.visionFor(custom, "qwen2-vl-7b", null))
        assertTrue(ModelCatalog.visionFor(custom, "llava-1.6", null))
        assertFalse(ModelCatalog.visionFor(custom, "mistral-7b-instruct", null))
    }

    // ── Refined vision: TTS / image-generation Gemini models are not screen-reading vision ─────
    @Test
    fun `gemini tts and image-generation models are not vision capable`() {
        assertFalse(ModelCatalog.geminiVision("gemini-2.5-flash-preview-tts"))
        assertFalse(ModelCatalog.geminiVision("gemini-2.5-flash-image"))
        assertFalse(ModelCatalog.geminiVision("gemini-2.0-flash-preview-image-generation"))
        // A normal generative flash/pro model stays vision-capable.
        assertTrue(ModelCatalog.geminiVision("gemini-2.5-flash"))
    }

    // ── Reasoning detection (id rules; JSON paths verified in the instrumented live test) ──────
    @Test
    fun `groq reasoning models are gpt-oss and qwen3 families only`() {
        assertTrue(ModelCatalog.groqReasoning("openai/gpt-oss-20b"))
        assertTrue(ModelCatalog.groqReasoning("openai/gpt-oss-120b"))
        assertTrue(ModelCatalog.groqReasoning("qwen/qwen3-32b"))
        assertFalse(ModelCatalog.groqReasoning("meta-llama/llama-4-scout-17b-16e-instruct"))
        assertFalse(ModelCatalog.groqReasoning("meta-llama/llama-prompt-guard-2-86m"))
    }

    @Test
    fun `openai reasoning models are o-series and gpt-5, not gpt-4o`() {
        assertTrue(ModelCatalog.openAiReasoning("o1"))
        assertTrue(ModelCatalog.openAiReasoning("o3-mini"))
        assertTrue(ModelCatalog.openAiReasoning("o4-mini"))
        assertTrue(ModelCatalog.openAiReasoning("gpt-5"))
        assertFalse(ModelCatalog.openAiReasoning("gpt-4o"))
        assertFalse(ModelCatalog.openAiReasoning("gpt-4.1"))
    }

    @Test
    fun `gemini reasoning is generation 2_5 plus, not 2_0 or 1_5`() {
        assertTrue(ModelCatalog.geminiReasoning("gemini-2.5-flash"))
        assertTrue(ModelCatalog.geminiReasoning("gemini-3-pro-preview"))
        assertFalse(ModelCatalog.geminiReasoning("gemini-2.0-flash"))
        assertFalse(ModelCatalog.geminiReasoning("gemini-1.5-pro"))
        assertFalse(ModelCatalog.geminiReasoning("gemini-2.5-flash-preview-tts"))
    }

    // ── canDisableThinking: only where the wire can truly turn thinking off ────────────────────
    @Test
    fun `canDisable is true for gemini flash and false for gemini pro and always-reasoning models`() {
        val gemini = LlmEndpointCatalog.builtinById("gemini")!!
        val openai = LlmEndpointCatalog.builtinById("openai")!!
        val groq = LlmEndpointCatalog.builtinById("groq")!!
        val openrouter = LlmEndpointCatalog.builtinById("openrouter")!!
        assertTrue(ModelCatalog.canDisableFor(gemini, "gemini-2.5-flash"))
        assertFalse(ModelCatalog.canDisableFor(gemini, "gemini-2.5-pro"))
        assertFalse("Gemini 3 uses thinking_level — no true off", ModelCatalog.canDisableFor(gemini, "gemini-3-flash-preview"))
        assertFalse(ModelCatalog.canDisableFor(openai, "o3-mini"))
        assertFalse(ModelCatalog.canDisableFor(groq, "openai/gpt-oss-20b"))
        assertTrue("OpenRouter always honors reasoning.enabled=false", ModelCatalog.canDisableFor(openrouter, "anything"))
    }

    // ── reasoningCapableForWire: the runtime gate (id-only; OpenRouter over-allows safely) ─────
    @Test
    fun `wire gate blocks reasoning on non-reasoning ids and allows it on reasoning ids`() {
        val groq = LlmEndpointCatalog.builtinById("groq")!!
        val openai = LlmEndpointCatalog.builtinById("openai")!!
        val gemini = LlmEndpointCatalog.builtinById("gemini")!!
        val openrouter = LlmEndpointCatalog.builtinById("openrouter")!!
        // Groq default (Llama 4) must be blocked — this is the byte-identical invariant guard.
        assertFalse(ModelCatalog.reasoningCapableForWire(groq, "meta-llama/llama-4-scout-17b-16e-instruct"))
        assertTrue(ModelCatalog.reasoningCapableForWire(groq, "openai/gpt-oss-20b"))
        assertFalse(ModelCatalog.reasoningCapableForWire(openai, "gpt-4o"))
        assertTrue(ModelCatalog.reasoningCapableForWire(openai, "o3-mini"))
        assertFalse(ModelCatalog.reasoningCapableForWire(gemini, "gemini-2.0-flash"))
        // OpenRouter over-allows on the id-only path (it normalizes/ignores) — never a false block.
        assertTrue(ModelCatalog.reasoningCapableForWire(openrouter, "some/text-only-model"))
    }

    // ── Free detection without pricing JSON (the :free id suffix path is JVM-safe) ─────────────
    @Test
    fun `free suffix ids are free and unknown pricing is null`() {
        assertEquals(true, ModelCatalog.freeFor("tencent/hy3:free", null))
        assertEquals(null, ModelCatalog.freeFor("openai/gpt-4o", null))
    }
}
