package com.aura.aura_ui.agent.llm

import ai.koog.prompt.llm.LLMCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — the capability that was computed, shown in the UI, and never consulted on the wire.
 *
 * `ModelCatalog` works out per model whether it can accept image parts, and the settings screen
 * displays it. `modelFor` then declared `Vision.Image` for **every** model id regardless, so a
 * non-vision model was sent a screenshot on every turn of every run.
 *
 * The gate is tri-state on purpose. Unknown must still declare vision: a false negative silently
 * turns off screen automation — the product's whole point — whereas a false positive costs one
 * honest, fast 400 (`KoogRetryPatternTest` measures that hard client errors are not retried).
 */
class VisionCapabilityGateTest {

    private fun capabilitiesFor(visionCapable: Boolean?): List<LLMCapability> =
        OpenAiCompatProvider.modelFor("some-model", visionCapable).capabilities.orEmpty()

    @Test fun `a vision-capable model declares image support`() {
        assertTrue(LLMCapability.Vision.Image in capabilitiesFor(true))
    }

    @Test fun `a model the provider says cannot see does NOT declare image support`() {
        assertFalse(LLMCapability.Vision.Image in capabilitiesFor(false))
    }

    /** The load-bearing default: unknown must never withhold vision. */
    @Test fun `an unknown capability still declares image support`() {
        assertTrue(LLMCapability.Vision.Image in capabilitiesFor(null))
    }

    @Test fun `the default argument is the unknown case, so old call sites are unchanged`() {
        assertTrue(LLMCapability.Vision.Image in OpenAiCompatProvider.modelFor("some-model").capabilities.orEmpty())
    }

    @Test fun `gating vision never disturbs the other capabilities`() {
        for (vision in listOf(true, false, null)) {
            val caps = capabilitiesFor(vision)
            assertTrue("tools must survive (vision=$vision)", LLMCapability.Tools in caps)
            assertTrue("completion must survive (vision=$vision)", LLMCapability.Completion in caps)
            assertTrue(
                "chat-completions routing must survive (vision=$vision)",
                LLMCapability.OpenAIEndpoint.Completions in caps,
            )
        }
    }

    @Test fun `the registry passes the capability through to the model`() {
        val gemini = LlmEndpointCatalog.builtinById("gemini")!!
        assertFalse(
            LLMCapability.Vision.Image in ProviderRegistry.modelFor(gemini, "m", visionCapable = false).capabilities.orEmpty(),
        )
        assertTrue(
            LLMCapability.Vision.Image in ProviderRegistry.modelFor(gemini, "m", visionCapable = null).capabilities.orEmpty(),
        )
    }
}
