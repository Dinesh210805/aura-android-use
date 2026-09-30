package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile facts ARE the spec of each provider's wire dialect — these tests are
 * executable documentation. If a fact changes (e.g. OpenRouter starts needing the
 * Gemini signature echo), change it HERE first.
 */
class ProviderProfileTest {

    @Test
    fun `groq is plain openai wire with reasoning_effort`() {
        val p = ProviderProfile.GROQ
        // Groq speaks OpenAI's reasoning_effort on its reasoning models (gpt-oss, qwen3); the control
        // is gated per-model so the non-reasoning Llama-4 default never sees the field.
        assertEquals(ReasoningRequestStyle.OPENAI_REASONING_EFFORT, p.reasoningRequestStyle)
        assertEquals(ReasoningResponseStyle.NONE, p.reasoningResponseStyle)
        assertFalse(p.requiresSignatureEcho)
        assertFalse(p.needsFinishReasonRepair)
        assertTrue(p.acceptsParallelToolCallsField)
    }

    @Test
    fun `openrouter returns reasoning_details and requires their echo`() {
        val p = ProviderProfile.OPENROUTER
        assertEquals(ReasoningResponseStyle.REASONING_DETAILS, p.reasoningResponseStyle)
        // D2 piece 2: OpenRouter carries the unified `reasoning` request param.
        assertEquals(ReasoningRequestStyle.OPENROUTER_REASONING, p.reasoningRequestStyle)
        assertTrue(p.supportsReasoningControl())
        assertFalse("signature echo is Gemini-direct only", p.requiresSignatureEcho)
        assertFalse(p.needsFinishReasonRepair)
        assertTrue(p.acceptsParallelToolCallsField)
    }

    @Test
    fun `gemini direct needs all three gemini shims`() {
        val p = ProviderProfile.GEMINI
        assertEquals(ReasoningRequestStyle.GEMINI_THINKING_CONFIG, p.reasoningRequestStyle)
        assertEquals(ReasoningResponseStyle.GEMINI_EXTRA_CONTENT, p.reasoningResponseStyle)
        assertTrue(p.requiresSignatureEcho)
        assertTrue(p.needsFinishReasonRepair)
        assertTrue(p.acceptsParallelToolCallsField)
    }

    @Test
    fun `openai carries reasoning_effort control and anthropic compat stays plain`() {
        assertEquals(ReasoningRequestStyle.OPENAI_REASONING_EFFORT, ProviderProfile.OPENAI.reasoningRequestStyle)
        assertTrue(ProviderProfile.OPENAI.supportsReasoningControl())
        assertEquals(ReasoningResponseStyle.NONE, ProviderProfile.ANTHROPIC.reasoningResponseStyle)
        assertFalse("anthropic compat surface unverified — no reasoning control yet", ProviderProfile.ANTHROPIC.supportsReasoningControl())
    }

    @Test
    fun `groq advertises reasoning control — safe because it is gated per-model`() {
        // Groq serves BOTH reasoning models (gpt-oss, qwen3) and non-reasoning ones (Llama 4). The
        // profile advertises reasoning_effort; the per-model gate (ModelInfo.reasoningCapable in the
        // UI + ModelCatalog.reasoningCapableForWire at runtime) keeps the field off Llama 4.
        assertEquals(ReasoningRequestStyle.OPENAI_REASONING_EFFORT, ProviderProfile.GROQ.reasoningRequestStyle)
        assertTrue(ProviderProfile.GROQ.supportsReasoningControl())
    }

    @Test
    fun `gemini supports reasoning control via thinking_config`() {
        assertTrue(ProviderProfile.GEMINI.supportsReasoningControl())
    }

    @Test
    fun `generation config defaults to no wire changes`() {
        val c = GenerationConfig()
        assertEquals(ReasoningLevel.DEFAULT, c.reasoning)
        assertEquals(null, c.temperature)
        assertTrue("untouched config must be a no-op on the wire", c.isDefault)
        assertFalse(GenerationConfig(reasoning = ReasoningLevel.LOW).isDefault)
        assertFalse(GenerationConfig(temperature = 0.2).isDefault)
    }
}
