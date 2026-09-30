package com.aura.aura_ui.mcp.bridge

import com.aura.aura_ui.agent.llm.LlmEndpoint
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agent.memory.EncryptedJsonStore

/**
 * Persists user-defined [LlmEndpoint]s (D2) as a single encrypted JSON list (via the generic
 * [EncryptedJsonStore]), and merges them with [LlmEndpointCatalog.BUILTINS] for the settings
 * picker + agent lookup. Built-ins are code, never stored; only custom endpoints round-trip here.
 * Reads are fail-soft (the underlying store returns an empty list on corrupt/absent JSON).
 */
class CustomEndpointStore(private val store: EncryptedJsonStore) {

    private val serializer = LlmEndpoint.serializer()

    fun list(): List<LlmEndpoint> = store.read(KEY, serializer).filter { !it.isBuiltIn }

    fun upsert(endpoint: LlmEndpoint) {
        val next = list().filter { it.id != endpoint.id } + endpoint.copy(isBuiltIn = false)
        store.write(KEY, next, serializer)
    }

    fun remove(id: String) = store.write(KEY, list().filter { it.id != id }, serializer)

    /** Built-ins first (stable order), then custom endpoints — the picker's display order. */
    fun allEndpoints(): List<LlmEndpoint> = LlmEndpointCatalog.BUILTINS + list()

    companion object {
        const val STORE_NAME = "aura_custom_endpoints"
        private const val KEY = "endpoints_json"
        fun newCustomId(seed: String): String =
            "custom:" + seed.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
    }
}
