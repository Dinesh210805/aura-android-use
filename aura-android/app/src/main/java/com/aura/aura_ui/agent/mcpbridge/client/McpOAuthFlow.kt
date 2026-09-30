package com.aura.aura_ui.agent.mcpbridge.client

import android.util.Base64
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Authorization-Code + PKCE flow logic for MCP OAuth (parent spec §3.6). Pure/testable: the
 * Custom Tab launch and the code->token HTTP exchange are thin glue done by the ViewModel.
 * Adversarial: the authorization endpoint must share the server's registrable domain, the
 * returned `state` must match, and the redirect must be our exact registered URI.
 */
object McpOAuthFlow {
    const val REDIRECT_URI = "aura://mcp/oauth"

    data class PendingAuth(val state: String, val codeVerifier: String, val authorizationUrl: String)

    fun begin(config: McpAuthConfig.OAuth, serverUrl: String): Result<PendingAuth> {
        if (!McpUrlValidator.sameRegistrableDomain(config.authorizationEndpoint, serverUrl)) {
            return Result.failure(IllegalArgumentException("Authorization endpoint domain does not match server"))
        }
        val state = randomUrlSafe(24)
        val verifier = randomUrlSafe(48)
        val challenge = s256(verifier)
        val url = buildString {
            append(config.authorizationEndpoint)
            append(if (config.authorizationEndpoint.contains('?')) '&' else '?')
            append("response_type=code")
            append("&client_id=").append(enc(config.clientId))
            append("&redirect_uri=").append(enc(REDIRECT_URI))
            append("&scope=").append(enc(config.scopes.joinToString(" ")))
            append("&state=").append(state)
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
        }
        return Result.success(PendingAuth(state, verifier, url))
    }

    fun validateRedirect(pending: PendingAuth, redirectUri: String, returnedState: String): Result<String> {
        if (!redirectUri.startsWith(REDIRECT_URI)) return Result.failure(IllegalArgumentException("Unexpected redirect URI"))
        if (returnedState != pending.state) return Result.failure(IllegalArgumentException("OAuth state mismatch"))
        val query = runCatching { URI(redirectUri).query ?: redirectUri.substringAfter('?', "") }.getOrDefault("")
        val params = query.split('&').mapNotNull { val p = it.split('='); if (p.size == 2) p[0] to p[1] else null }.toMap()
        val code = params["code"] ?: return Result.failure(IllegalArgumentException("No authorization code in redirect"))
        return Result.success(code)
    }

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
