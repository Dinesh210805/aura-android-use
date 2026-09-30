package com.aura.aura_ui.agent.mcpbridge.client

import java.net.URI

/**
 * Adversarial URL gate for user-supplied MCP server URLs (parent spec §4, SSRF discipline).
 * https-only by default; a single localhost exception exists for debug builds. OAuth endpoints
 * are bound to the server's registrable domain via [sameRegistrableDomain] so a malicious server
 * cannot point its token endpoint at an attacker-controlled host.
 */
object McpUrlValidator {
    private val LOCALHOSTS = setOf("localhost", "127.0.0.1", "::1")

    fun validate(url: String, allowLocalhostDebug: Boolean = false): Result<Unit> {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("URL is not parseable"))
        val host = uri.host ?: return Result.failure(IllegalArgumentException("URL has no host"))
        return when (val scheme = uri.scheme?.lowercase()) {
            "https" -> Result.success(Unit)
            "http" -> if (allowLocalhostDebug && host in LOCALHOSTS) Result.success(Unit)
                else Result.failure(IllegalArgumentException("Only https URLs are allowed"))
            else -> Result.failure(IllegalArgumentException("Unsupported URL scheme: $scheme"))
        }
    }

    /** True when [a] and [b] share the last two host labels (registrable domain heuristic). */
    fun sameRegistrableDomain(a: String, b: String): Boolean {
        val ha = runCatching { URI(a).host }.getOrNull() ?: return false
        val hb = runCatching { URI(b).host }.getOrNull() ?: return false
        return ha.split('.').takeLast(2) == hb.split('.').takeLast(2)
    }
}
