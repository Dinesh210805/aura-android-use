package com.aura.mcp.bridge

/**
 * Per-call principal carried on the request coroutine context so the tool
 * dispatcher can enforce per-tool scopes and attribute audit-log entries.
 *
 * For WebRTC clients this is a fixed full-scope identity built when the device
 * approves the connection (see `McpServerController.startWebRtc`).
 *
 * @param tokenId opaque short id used in audit logs without exposing the full
 *   secret (WebRTC: a short prefix of the client token)
 * @param scopes capability set granted to this principal — see [McpScope]
 * @param label optional human label shown in the activity log
 */
data class TokenPrincipal(
    val tokenId: String,
    val scopes: Set<McpScope>,
    val label: String?,
)

/**
 * Capability scopes a principal can carry. Kept small intentionally —
 * each new scope is a permission boundary the user has to reason about.
 *
 * * [READ]  — passive inspection only (screenshots, UI tree, web search, status)
 * * [WRITE] — active device control (taps, swipes, app launch, key events)
 */
enum class McpScope {
    READ,
    WRITE,
}
