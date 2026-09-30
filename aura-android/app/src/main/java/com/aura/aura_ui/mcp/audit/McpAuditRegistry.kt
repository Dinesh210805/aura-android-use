package com.aura.aura_ui.mcp.audit

/**
 * Process-global handle to the [RingBufferAuditLogger] used by the on-device
 * MCP server.
 *
 * Why a singleton: the foreground service hands one logger instance to the
 * [com.aura.mcp.McpServerController] at construction time, and the UI's
 * Activity Log screen (instantiated by Compose, with no DI access to the
 * service-scoped object) needs to subscribe to the same instance to render
 * live entries. A singleton is the simplest reliable way to bridge those
 * two scopes in this codebase, given the existing Hilt setup doesn't span
 * the service ↔ activity boundary cleanly.
 *
 * Kept very small intentionally — just a lazy field. Tests can swap in a
 * different logger by reading [INSTANCE].
 */
object McpAuditRegistry {
    val INSTANCE: RingBufferAuditLogger by lazy { RingBufferAuditLogger() }
}
