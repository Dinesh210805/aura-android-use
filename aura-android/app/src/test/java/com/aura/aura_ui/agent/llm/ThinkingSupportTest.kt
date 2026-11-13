package com.aura.aura_ui.agent.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import com.aura.aura_ui.agent.llm.ReasoningLevel.DEFAULT
import com.aura.aura_ui.agent.llm.ReasoningLevel.HIGH
import com.aura.aura_ui.agent.llm.ReasoningLevel.LOW
import com.aura.aura_ui.agent.llm.ReasoningLevel.MEDIUM
import com.aura.aura_ui.agent.llm.ReasoningLevel.OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The thinking levels each model is offered, and what Claude is sent for each. */
class ThinkingSupportTest {
    private fun endpoint(id: String) = requireNotNull(LlmEndpointCatalog.builtinById(id))

    @Test
    fun `each provider is offered only the levels its wire accepts`() {
        val gemini = endpoint(LlmEndpointCatalog.GEMINI_ID)
        assertEquals(listOf(DEFAULT, LOW, HIGH), ThinkingSupport.levelsFor(gemini, "gemini-3.5-flash-lite"))
        assertEquals(listOf(DEFAULT, OFF, LOW, MEDIUM, HIGH), ThinkingSupport.levelsFor(gemini, "gemini-2.5-flash"))
        assertEquals(listOf(DEFAULT, LOW, MEDIUM, HIGH), ThinkingSupport.levelsFor(gemini, "gemini-2.5-pro"))
        assertEquals(listOf(DEFAULT), ThinkingSupport.levelsFor(gemini, "gemini-2.0-flash"))

        assertEquals(listOf(DEFAULT, LOW, MEDIUM, HIGH), ThinkingSupport.levelsFor(endpoint(LlmEndpointCatalog.OPENAI_ID), "o4-mini"))
        assertEquals(listOf(DEFAULT), ThinkingSupport.levelsFor(endpoint(LlmEndpointCatalog.OPENAI_ID), "gpt-4.1"))
        assertEquals(listOf(DEFAULT), ThinkingSupport.levelsFor(endpoint(LlmEndpointCatalog.GROQ_ID), LlmEndpointCatalog.DEFAULT_MODEL_ID))

        val claude = endpoint(LlmEndpointCatalog.ANTHROPIC_ID)
        assertEquals(listOf(OFF, LOW, MEDIUM, HIGH), ThinkingSupport.levelsFor(claude, "claude-sonnet-4-5-20250929"))
        assertEquals(listOf(DEFAULT), ThinkingSupport.levelsFor(claude, "claude-3-5-haiku-20241022"))
    }

    @Test
    fun `what the listing said wins over the id rules`() {
        val openRouter = endpoint(LlmEndpointCatalog.OPENROUTER_ID)
        assertEquals(listOf(DEFAULT), ThinkingSupport.levelsFor(openRouter, "some/model", reasoning = false))
        assertEquals(listOf(DEFAULT, OFF, LOW, MEDIUM, HIGH), ThinkingSupport.levelsFor(openRouter, "some/model", reasoning = true, canDisable = true))
    }

    @Test
    fun `a saved level moves to the nearest one the new model accepts`() {
        assertEquals(LOW, ThinkingSupport.clamp(MEDIUM, listOf(DEFAULT, LOW, HIGH)))
        assertEquals(LOW, ThinkingSupport.clamp(OFF, listOf(DEFAULT, LOW, HIGH)))
        assertEquals(OFF, ThinkingSupport.clamp(DEFAULT, listOf(OFF, LOW, MEDIUM, HIGH)))
        assertEquals(DEFAULT, ThinkingSupport.clamp(HIGH, listOf(DEFAULT)))
        assertEquals(OFF, ThinkingSupport.lightest(listOf(DEFAULT, OFF, LOW)))
        assertEquals(LOW, ThinkingSupport.lightest(listOf(DEFAULT, LOW, HIGH)))
    }

    @Test
    fun `temperature is dropped where the model rejects it`() {
        val openAi = endpoint(LlmEndpointCatalog.OPENAI_ID)
        assertFalse(ThinkingSupport.temperatureAllowed(openAi, "gpt-5-mini", DEFAULT))
        assertTrue(ThinkingSupport.temperatureAllowed(openAi, "gpt-4.1", DEFAULT))
        val claude = endpoint(LlmEndpointCatalog.ANTHROPIC_ID)
        assertFalse(ThinkingSupport.temperatureAllowed(claude, "claude-opus-4-1", HIGH))
        assertTrue(ThinkingSupport.temperatureAllowed(claude, "claude-opus-4-1", OFF))
    }

    @Test
    fun `claude gets a thinking budget under its output cap, and no temperature while thinking`() {
        val model = AnthropicNative.model("claude-sonnet-4-5-20250929", vision = true)
        val thinking = AnthropicNative.params(model, GenerationConfig(reasoning = HIGH, temperature = 0.3)) as AnthropicParams
        val budget = (thinking.thinking as AnthropicThinking.Enabled).budgetTokens
        assertTrue(requireNotNull(thinking.maxTokens) > budget)
        assertNull(thinking.temperature)
        assertTrue(thinking.cacheControl != null)

        val plain = AnthropicNative.params(model, GenerationConfig(reasoning = OFF, temperature = 0.3)) as AnthropicParams
        assertNull(plain.thinking)
        assertEquals(0.3, requireNotNull(plain.temperature), 0.0)
        assertTrue(requireNotNull(plain.maxTokens) > 2_048)
    }

    @Test
    fun `chat models on the native wire are served by the client built for them`() {
        val chat = ProviderRegistry.chatModel(
            endpoint(LlmEndpointCatalog.ANTHROPIC_ID), "key", "claude-haiku-4-5-20251001", true, GenerationConfig(reasoning = MEDIUM),
        )
        assertEquals(ai.koog.prompt.llm.LLMProvider.Anthropic, chat.model.provider)
        assertTrue((chat.params as AnthropicParams).thinking is AnthropicThinking.Enabled)
    }
}
