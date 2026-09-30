package com.aura.aura_ui.agent.mcpbridge.client

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists the list of configured MCP servers (no secrets) as JSON in plain prefs. */
class McpServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("aura_mcp_servers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Dto(
        val id: String, val displayName: String, val url: String,
        val transport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
        val enabled: Boolean = true,
        val oauth: OAuthDto? = null,
        val hasBearer: Boolean = false,
    )

    @Serializable
    private data class OAuthDto(val authorizationEndpoint: String, val tokenEndpoint: String, val clientId: String, val scopes: List<String>)

    fun list(): List<McpServerConfig> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Dto>>(raw) }.getOrDefault(emptyList()).map { it.toConfig() }
    }

    fun upsert(config: McpServerConfig) {
        val next = (list().filterNot { it.id == config.id } + config).map { it.toDto() }
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
    }

    fun delete(id: String) {
        val next = list().filterNot { it.id == id }.map { it.toDto() }
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        list().firstOrNull { it.id == id }?.let { upsert(it.copy(enabled = enabled)) }
    }

    private fun McpServerConfig.toDto() = Dto(
        id, displayName, url, transport, enabled,
        oauth = (auth as? McpAuthConfig.OAuth)?.let { OAuthDto(it.authorizationEndpoint, it.tokenEndpoint, it.clientId, it.scopes) },
        hasBearer = auth is McpAuthConfig.BearerToken,
    )

    private fun Dto.toConfig() = McpServerConfig(
        id = id, displayName = displayName, url = url, transport = transport, enabled = enabled,
        auth = when {
            oauth != null -> McpAuthConfig.OAuth(oauth.authorizationEndpoint, oauth.tokenEndpoint, oauth.clientId, oauth.scopes)
            hasBearer -> McpAuthConfig.BearerToken(tokenRef = id)
            else -> McpAuthConfig.None
        },
    )

    private companion object { const val KEY = "servers_json" }
}
