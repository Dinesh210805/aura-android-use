package com.aura.aura_ui.agent.mcpbridge.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpUrlValidatorTest {
    @Test fun `https url is accepted`() {
        assertTrue(McpUrlValidator.validate("https://mcp.example.com/v1").isSuccess)
    }

    @Test fun `http url is rejected without debug localhost`() {
        assertTrue(McpUrlValidator.validate("http://mcp.example.com").isFailure)
    }

    @Test fun `http localhost is accepted only when debug allowed`() {
        assertTrue(McpUrlValidator.validate("http://localhost:8000", allowLocalhostDebug = true).isSuccess)
        assertTrue(McpUrlValidator.validate("http://localhost:8000", allowLocalhostDebug = false).isFailure)
    }

    @Test fun `garbage url is rejected`() {
        assertTrue(McpUrlValidator.validate("not a url").isFailure)
    }

    @Test fun `same registrable domain compares hosts`() {
        assertTrue(McpUrlValidator.sameRegistrableDomain("https://a.example.com", "https://auth.example.com"))
        assertFalse(McpUrlValidator.sameRegistrableDomain("https://example.com", "https://evil.com"))
    }
}
