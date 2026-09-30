package com.aura.aura_ui.agent.mcpbridge.client

/**
 * Stateful seam that bridges the (pure, stateless) [McpOAuthFlow] to the Android redirect leg:
 * it holds the single in-flight [McpOAuthFlow.PendingAuth] between launching the authorization
 * page and the `aura://mcp/oauth` redirect coming back through MainActivity.
 *
 * Security posture: the PKCE verifier + `state` live in memory only (never persisted), the
 * pending handshake is **consumed on completion** (no replay), and a redirect that arrives with
 * no handshake in flight or a mismatched state is rejected. The code→token exchange + secret
 * persistence are the remaining glue (intentionally not here).
 */
object McpOAuthCoordinator {

    /** A validated authorization code tied to the server that requested it. */
    data class AuthCode(val serverId: String, val code: String)

    @Volatile private var pending: Entry? = null

    private data class Entry(val serverId: String, val auth: McpOAuthFlow.PendingAuth)

    /** Begin an Authorization-Code + PKCE handshake for [serverId]; returns the authorization URL. */
    @Synchronized
    fun begin(serverId: String, config: McpAuthConfig.OAuth, serverUrl: String): Result<String> =
        McpOAuthFlow.begin(config, serverUrl).map { p ->
            pending = Entry(serverId, p)
            p.authorizationUrl
        }

    /** Complete the handshake from the redirect URI. Consumes the pending handshake on success. */
    @Synchronized
    fun completeRedirect(redirectUri: String): Result<AuthCode> {
        val entry = pending
            ?: return Result.failure(IllegalStateException("No OAuth handshake in progress"))
        val returnedState = redirectUri.substringAfter("state=", "").substringBefore("&")
        return McpOAuthFlow.validateRedirect(entry.auth, redirectUri, returnedState).map { code ->
            pending = null // one-shot: consume so a replayed redirect cannot mint a second code
            AuthCode(entry.serverId, code)
        }
    }

    /** True while a handshake is awaiting its redirect. */
    fun isPending(): Boolean = pending != null

    /** Test/utility hook to clear any in-flight handshake. */
    @Synchronized
    fun reset() {
        pending = null
    }
}
