package com.aura.mcp.bridge

/**
 * Port for recording every MCP tool dispatch — successes, failures, scope
 * denials. The `:app` layer implements this with a bounded ring buffer that
 * the UI's "Activity Log" screen subscribes to. The interface stays in
 * `:mcp-server` so the scope guard / tool dispatcher can call it without
 * pulling in any Android dependency.
 *
 * Calls must be cheap and non-blocking — they happen on the hot dispatch
 * path. Implementations should drop the oldest entry when full rather than
 * block the caller.
 */
interface McpAuditLogger {

    /**
     * Record a single tool invocation.
     *
     * @param toolName the registered MCP tool name (e.g. `"tap"`, `"web_search"`)
     * @param tokenId short opaque id of the calling principal — matches
     *   [TokenPrincipal.tokenId]; `null` if the call somehow ran without auth
     *   (shouldn't happen in production, useful for tests)
     * @param success true if the handler returned `isError = false`
     * @param scopeDenied true if the call was rejected for missing scope
     *   (distinct from success=false, which covers business-logic failures)
     * @param durationMs wall-clock time spent in the handler (or scope check)
     * @param errorSummary short human-readable error string when [success]
     *   is false — never the full exception trace, never PII
     */
    fun log(
        toolName: String,
        tokenId: String?,
        success: Boolean,
        scopeDenied: Boolean,
        durationMs: Long,
        errorSummary: String? = null,
    )

    /**
     * Record a connection lifecycle event (trust & transparency). Distinct
     * from [log] — these are about *who connected*, not *what they called*.
     * Default no-op so existing implementations compile unchanged.
     *
     * @param event   what happened — see [ConnectionEventType]
     * @param tokenId short client-token prefix (matches [TokenPrincipal.tokenId])
     * @param label   self-declared client name (display only)
     * @param host    self-declared PC host name (display only)
     * @param platform self-declared OS (display only)
     */
    fun logConnection(
        event: ConnectionEventType,
        tokenId: String?,
        label: String?,
        host: String?,
        platform: String?,
    ) = Unit
}

/** Connection lifecycle events surfaced in the audit log. */
enum class ConnectionEventType {
    /** A previously-trusted token reconnected — no dialog shown. */
    AUTO_APPROVED,

    /** The user approved a new client at the on-device dialog. */
    APPROVED,

    /** The user denied a client at the on-device dialog. */
    DENIED,

    /** A connected client's session ended (clean, drop, or user disconnect). */
    DISCONNECTED,
}

/** No-op default — used in tests or when the host doesn't wire an audit sink. */
object NoOpAuditLogger : McpAuditLogger {
    override fun log(
        toolName: String,
        tokenId: String?,
        success: Boolean,
        scopeDenied: Boolean,
        durationMs: Long,
        errorSummary: String?,
    ) = Unit
}
