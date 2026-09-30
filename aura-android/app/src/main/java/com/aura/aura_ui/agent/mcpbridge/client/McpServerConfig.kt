package com.aura.aura_ui.agent.mcpbridge.client

/**
 * Outbound MCP client model (Sub-project #2). Describes a third-party MCP server the
 * on-device agent can connect to. Secrets are NEVER held here — [McpAuthConfig.BearerToken]
 * carries only a reference into the encrypted [McpSecretStore]; the config list itself is
 * non-secret and JSON-persisted by [McpServerStore].
 */
enum class McpTransportType { STREAMABLE_HTTP, SSE, WEBSOCKET }

/** v1 only supports user-added remote servers; the enum leaves room for future scopes. */
enum class McpServerScope { USER }

sealed interface McpAuthConfig {
    data object None : McpAuthConfig

    /** [tokenRef] is a key into [McpSecretStore], not the token itself. */
    data class BearerToken(val tokenRef: String) : McpAuthConfig

    data class OAuth(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val clientId: String,
        val scopes: List<String>,
    ) : McpAuthConfig
}

data class McpServerConfig(
    val id: String,
    val displayName: String,
    val url: String,
    val transport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val enabled: Boolean = true,
    val auth: McpAuthConfig = McpAuthConfig.None,
    val scope: McpServerScope = McpServerScope.USER,
)
