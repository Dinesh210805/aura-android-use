package com.aura.mcp.bridge

/**
 * Identity of a connecting MCP client, captured at connect time so the host
 * app can show the user *exactly who* is driving the device.
 *
 * These fields come from the `init` handshake the `aura-mcp-connect` bridge
 * sends over the DataChannel. They are self-declared and only for display.
 *
 * @param tokenId      stable id for the client (WebRTC: short client-token prefix)
 * @param agentLabel   human name to show ("AURA MCP Bridge", a paired label, …)
 * @param remoteAddr   network origin if known (WebRTC: the PC host name)
 * @param clientVersion bridge/client version string, if reported
 * @param host         the PC's host name, if reported
 * @param platform     the PC's OS platform (win32 / darwin / linux), if reported
 * @param transport    how this client is connected, for display
 */
data class ClientInfo(
    val tokenId: String,
    val agentLabel: String,
    val remoteAddr: String? = null,
    val clientVersion: String? = null,
    val host: String? = null,
    val platform: String? = null,
    val transport: String = "WebRTC",
)

/**
 * Notified by [com.aura.mcp.McpServerController] whenever an MCP client
 * connects, sees activity, or disconnects. The implementation in the app
 * surfaces this as the "Connected" list in the MCP Center screen.
 *
 * Default: [NoOpConnectionTracker] — server runs unchanged when the host
 * app doesn't care about per-client tracking.
 */
interface ConnectionTracker {

    /**
     * Called once a client is connected and approved.
     *
     * @return an opaque session key the controller stashes so it can later
     *   request a disconnect of *this specific* session.
     */
    fun onConnect(info: ClientInfo): String

    /** Bump the session's lastActivity timestamp. Cheap — called per tool dispatch. */
    fun onActivity(sessionKey: String)

    /** Session ended — clean disconnect, network drop, or user-requested cancel. */
    fun onDisconnect(sessionKey: String)
}

object NoOpConnectionTracker : ConnectionTracker {
    override fun onConnect(info: ClientInfo): String = ""
    override fun onActivity(sessionKey: String) = Unit
    override fun onDisconnect(sessionKey: String) = Unit
}
