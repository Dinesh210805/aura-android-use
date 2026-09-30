package com.aura.aura_ui.mcp.bridge

import com.aura.aura_ui.agent.llm.GenerationConfig
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.llm.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration contract (D2): re-keying ProviderKeyStore by endpoint-id string must reproduce the
 * legacy per-enum pref names byte-for-byte, and the legacy uppercase `provider_selected` value
 * must still resolve. Both are exercised through the pure companion helpers so no Android runtime
 * is needed — a user upgrading keeps their saved key.
 */
class ProviderKeyStoreMigrationTest {

    @Test
    fun `key pref name equals the legacy enum-derived name`() {
        // Legacy: "provider_key_" + LlmProvider.GROQ.name.lowercase() == "provider_key_groq"
        assertEquals("provider_key_groq", ProviderKeyStore.keyNameFor(LlmEndpointCatalog.GROQ_ID))
        assertEquals("provider_model_gemini", ProviderKeyStore.keyModelFor(LlmEndpointCatalog.GEMINI_ID))
    }

    @Test
    fun `legacy uppercase selected-provider value resolves to the endpoint id`() {
        assertEquals("groq", ProviderKeyStore.normalizeSelectedId("GROQ"))
        assertEquals("openrouter", ProviderKeyStore.normalizeSelectedId("OPENROUTER"))
        // A new-style id passes through untouched.
        assertEquals("custom:mine", ProviderKeyStore.normalizeSelectedId("custom:mine"))
    }

    // ── D2 piece 2: generation-config storage fails safe to defaults. ──

    @Test
    fun `absent generation config decodes to defaults (upgrade = unchanged behavior)`() {
        assertTrue("no stored config → byte-identical request", ProviderKeyStore.decodeGeneration(null).isDefault)
    }

    @Test
    fun `corrupt generation config decodes to defaults`() {
        assertTrue(ProviderKeyStore.decodeGeneration("{ not valid json").isDefault)
    }

    @Test
    fun `generation config round-trips through json`() {
        val json = """{"reasoning":"HIGH","temperature":0.2}"""
        val decoded = ProviderKeyStore.decodeGeneration(json)
        assertEquals(ReasoningLevel.HIGH, decoded.reasoning)
        assertEquals(0.2, decoded.temperature!!, 0.0001)
    }

    @Test
    fun `gen pref name is namespaced per endpoint`() {
        assertEquals("provider_gen_groq", ProviderKeyStore.keyGenFor(LlmEndpointCatalog.GROQ_ID))
    }
}
