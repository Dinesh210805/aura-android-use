package com.aura.aura_ui.agent.mcpbridge.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class) // McpOAuthFlow.begin uses android.util.Base64
class McpOAuthCoordinatorTest {
    private val oauth = McpAuthConfig.OAuth(
        authorizationEndpoint = "https://auth.example.com/authorize",
        tokenEndpoint = "https://auth.example.com/token",
        clientId = "abc", scopes = listOf("repo"),
    )

    @After fun cleanup() = McpOAuthCoordinator.reset()

    @Test fun `begin stores pending and completeRedirect returns the code`() {
        val url = McpOAuthCoordinator.begin("gh", oauth, "https://mcp.example.com").getOrThrow()
        val state = url.substringAfter("state=").substringBefore("&")
        val code = McpOAuthCoordinator.completeRedirect("aura://mcp/oauth?code=XYZ&state=$state").getOrThrow()
        assertEquals("gh", code.serverId)
        assertEquals("XYZ", code.code)
    }

    @Test fun `completeRedirect fails when nothing is in flight`() {
        assertTrue(McpOAuthCoordinator.completeRedirect("aura://mcp/oauth?code=XYZ&state=whatever").isFailure)
    }

    @Test fun `completeRedirect fails on state mismatch`() {
        McpOAuthCoordinator.begin("gh", oauth, "https://mcp.example.com").getOrThrow()
        assertTrue(McpOAuthCoordinator.completeRedirect("aura://mcp/oauth?code=XYZ&state=forged").isFailure)
    }

    @Test fun `begin rejects cross-domain endpoint`() {
        val evil = oauth.copy(authorizationEndpoint = "https://evil.com/authorize")
        assertTrue(McpOAuthCoordinator.begin("gh", evil, "https://mcp.example.com").isFailure)
    }

    @Test fun `completeRedirect consumes the pending so a replay fails`() {
        val url = McpOAuthCoordinator.begin("gh", oauth, "https://mcp.example.com").getOrThrow()
        val state = url.substringAfter("state=").substringBefore("&")
        val redirect = "aura://mcp/oauth?code=XYZ&state=$state"
        assertTrue(McpOAuthCoordinator.completeRedirect(redirect).isSuccess)
        assertTrue(McpOAuthCoordinator.completeRedirect(redirect).isFailure) // pending already consumed
    }
}
