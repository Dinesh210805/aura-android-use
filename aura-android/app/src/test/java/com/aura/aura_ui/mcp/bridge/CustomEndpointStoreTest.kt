package com.aura.aura_ui.mcp.bridge

import com.aura.aura_ui.agent.llm.AuthScheme
import com.aura.aura_ui.agent.llm.LlmEndpoint
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.llm.ModelListStyle
import com.aura.aura_ui.agent.llm.ProviderProfile
import com.aura.aura_ui.agent.llm.VisionInference
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CustomEndpointStoreTest {

    private fun store(name: String) =
        CustomEndpointStore(EncryptedJsonStore(RuntimeEnvironment.getApplication(), name))

    private fun custom(id: String, name: String) = LlmEndpoint(
        id = id,
        displayName = name,
        baseUrl = "https://example.com",
        chatCompletionsPath = "/v1/chat/completions",
        modelsUrl = "https://example.com/v1/models",
        authScheme = AuthScheme.BEARER,
        modelListStyle = ModelListStyle.OPENAI_DATA,
        visionInference = VisionInference.GENERIC,
        profile = ProviderProfile(),
        isBuiltIn = false,
    )

    @Test
    fun `upsert then list round-trips a custom endpoint`() {
        val s = store("ce_rt")
        s.upsert(custom("custom:mine", "My Server"))
        assertEquals(listOf("custom:mine"), s.list().map { it.id })
        assertEquals("My Server", s.list().first().displayName)
    }

    @Test
    fun `upsert replaces an endpoint with the same id`() {
        val s = store("ce_replace")
        s.upsert(custom("custom:a", "First"))
        s.upsert(custom("custom:a", "Renamed"))
        assertEquals(1, s.list().size)
        assertEquals("Renamed", s.list().first().displayName)
    }

    @Test
    fun `remove deletes by id`() {
        val s = store("ce_remove")
        s.upsert(custom("custom:a", "A"))
        s.upsert(custom("custom:b", "B"))
        s.remove("custom:a")
        assertEquals(listOf("custom:b"), s.list().map { it.id })
    }

    @Test
    fun `allEndpoints lists built-ins first then custom`() {
        val s = store("ce_all")
        s.upsert(custom("custom:z", "Z"))
        val ids = s.allEndpoints().map { it.id }
        assertEquals(LlmEndpointCatalog.BUILTINS.map { it.id }, ids.dropLast(1))
        assertEquals("custom:z", ids.last())
    }

    @Test
    fun `newCustomId is namespaced and slugged`() {
        assertEquals("custom:my-server", CustomEndpointStore.newCustomId("My Server!"))
    }
}
