package com.aura.mcp.bridge

/**
 * Per-call principal carried on the request coroutine context so the tool
 * dispatcher can enforce per-tool scopes and attribute audit-log entries.
 *
 * For a PC this is a full-scope identity built when the phone approves it
 * (`McpServerController.bindFreshMcpSession`).
 *
 * @param tokenId short id used in audit logs without exposing the secret
 *   (for a PC: the first 8 chars of its pairing token)
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
