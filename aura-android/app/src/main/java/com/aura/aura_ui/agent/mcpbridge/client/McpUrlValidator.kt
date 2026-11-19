package com.aura.aura_ui.agent.mcpbridge.client

import java.net.URI

/**
 * Adversarial URL gate for user-supplied MCP server URLs (parent spec §4, SSRF discipline).
 * https-only by default; a localhost/loopback exception exists for debug builds. Private LAN
 * IPs (RFC 1918: 10.x, 172.16-31.x, 192.168.x) are allowed over http when [allowPrivateLan]
 * is true — this is the Ollama-on-laptop case, where the server cannot serve TLS. OAuth
 * endpoints are bound to the server's registrable domain via [sameRegistrableDomain] so a
 * malicious server cannot point its token endpoint at an attacker-controlled host.
 */
object McpUrlValidator {
    private val LOCALHOSTS = setOf("localhost", "127.0.0.1", "::1")

    /** True when [host] is an RFC 1918 private LAN address (not loopback). */
    fun isPrivateLan(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when (parts[0]) {
            10 -> true
            172 -> parts[1] in 16..31
            192 -> parts[1] == 168
            else -> false
        }
    }

    fun validate(
        url: String,
        allowLocalhostDebug: Boolean = false,
        allowPrivateLan: Boolean = false,
    ): Result<Unit> {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("URL is not parseable"))
        val host = uri.host ?: return Result.failure(IllegalArgumentException("URL has no host"))
        return when (val scheme = uri.scheme?.lowercase()) {
            "https" -> Result.success(Unit)
            "http" -> when {
                allowLocalhostDebug && host in LOCALHOSTS -> Result.success(Unit)
                allowPrivateLan && isPrivateLan(host) -> Result.success(Unit)
                else -> Result.failure(
                    IllegalArgumentException(
                        "Only https URLs are allowed. " +
                            "For a local server on your network, use a private IP (e.g. http://192.168.x.x:11434)."
                    )
                )
            }
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
