package com.aura.aura_ui.agent.mcpbridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class) // real android.util.Base64 for genuine PKCE values
class McpOAuthFlowTest {
    private val oauth = McpAuthConfig.OAuth(
        authorizationEndpoint = "https://auth.example.com/authorize",
        tokenEndpoint = "https://auth.example.com/token",
        clientId = "abc", scopes = listOf("repo"),
    )

    @Test fun `begin builds pkce authorization url for same-domain endpoint`() {
        val p = McpOAuthFlow.begin(oauth, serverUrl = "https://mcp.example.com").getOrThrow()
        assertTrue(p.authorizationUrl.startsWith("https://auth.example.com/authorize"))
        assertTrue(p.authorizationUrl.contains("code_challenge="))
        assertTrue(p.authorizationUrl.contains("state=${p.state}"))
        assertTrue(p.authorizationUrl.contains("redirect_uri="))
    }

    @Test fun `begin rejects cross-domain authorization endpoint`() {
        val evil = oauth.copy(authorizationEndpoint = "https://evil.com/authorize")
        assertTrue(McpOAuthFlow.begin(evil, serverUrl = "https://mcp.example.com").isFailure)
    }

    @Test fun `validateRedirect returns code when state matches`() {
        val p = McpOAuthFlow.begin(oauth, "https://mcp.example.com").getOrThrow()
        val code = McpOAuthFlow.validateRedirect(p, "aura://mcp/oauth?code=XYZ&state=${p.state}", p.state).getOrThrow()
        assertEquals("XYZ", code)
    }

    @Test fun `validateRedirect rejects state mismatch`() {
        val p = McpOAuthFlow.begin(oauth, "https://mcp.example.com").getOrThrow()
        assertTrue(McpOAuthFlow.validateRedirect(p, "aura://mcp/oauth?code=XYZ&state=bad", "bad").isFailure)
    }
}
