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

    // ── LAN / private-IP carve-out (Ollama) ───────────────────────────────────

    @Test fun `private LAN IPs accepted over http when allowPrivateLan is true`() {
        assertTrue(McpUrlValidator.validate("http://192.168.1.42:11434", allowPrivateLan = true).isSuccess)
        assertTrue(McpUrlValidator.validate("http://10.0.0.5:11434", allowPrivateLan = true).isSuccess)
        assertTrue(McpUrlValidator.validate("http://172.20.0.1:11434", allowPrivateLan = true).isSuccess)
    }

    @Test fun `private LAN IPs rejected over http when allowPrivateLan is false`() {
        assertTrue(McpUrlValidator.validate("http://192.168.1.42:11434", allowPrivateLan = false).isFailure)
    }

    @Test fun `public IP is rejected even when allowPrivateLan is true`() {
        // allowPrivateLan is not a blanket http permit — only RFC 1918 addresses.
        assertTrue(McpUrlValidator.validate("http://1.2.3.4:11434", allowPrivateLan = true).isFailure)
    }

    @Test fun `isPrivateLan classifies RFC 1918 ranges correctly`() {
        assertTrue(McpUrlValidator.isPrivateLan("192.168.0.1"))
        assertTrue(McpUrlValidator.isPrivateLan("10.0.0.1"))
        assertTrue(McpUrlValidator.isPrivateLan("172.16.0.1"))
        assertTrue(McpUrlValidator.isPrivateLan("172.31.255.255"))
        assertFalse(McpUrlValidator.isPrivateLan("172.15.0.1"))   // just outside 16-31
        assertFalse(McpUrlValidator.isPrivateLan("172.32.0.1"))   // just outside 16-31
        assertFalse(McpUrlValidator.isPrivateLan("8.8.8.8"))
    }
}
