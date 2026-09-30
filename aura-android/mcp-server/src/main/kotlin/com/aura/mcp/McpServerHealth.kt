package com.aura.mcp

/**
 * Observable runtime state of the on-device MCP server. Replaces the old
 * `Boolean isRunning` getter so the foreground service / UI can show precise
 * status (and a real failure reason) instead of guessing.
 *
 * The values are produced by [McpServerController] and observed via its
 * `health: StateFlow<McpServerHealth>`. A single source of truth — the
 * notification chip, the in-app status badge, and any future health probe
 * should all read this, never derive their own opinion from side channels.
 */
sealed interface McpServerHealth {

    /** Server has never been started in this process, or was stopped cleanly. */
    data object Stopped : McpServerHealth

    /** Reserved for a start that is still binding its port; the current start is synchronous. */
    data object Starting : McpServerHealth

    /**
     * Server is bound and accepting connections from the local network.
     *
     * @param port the LAN signaling port (see `LanPolicy.PORTS`)
     * @param host always `"LAN"`: the server is reachable only from the phone's local networks
     * @param reachableAddresses the phone's LAN IPv4 addresses (Wi-Fi, hotspot, VPN such as
     *   Tailscale). Updated when the phone changes network.
     * @param webRtcPin the current pairing PIN. Single use: it changes after each pairing and
     *   after too many wrong guesses.
     */
    data class Listening(
        val port: Int,
        val host: String,
        val reachableAddresses: List<String>,
        val webRtcPin: String? = null
    ) : McpServerHealth

    /**
     * Startup or runtime failure. Includes the human-readable reason for the
     * UI to surface ("Couldn't open the local network port", etc.)
     * and the throwable for log-only consumption.
     */
    data class Crashed(
        val reason: String,
        val cause: Throwable? = null,
    ) : McpServerHealth
}
