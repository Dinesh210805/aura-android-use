package com.aura.mcp.tools

import com.aura.mcp.bridge.NotificationActionSpec
import com.aura.mcp.bridge.NotificationSnapshot

/**
 * Pure selection/shaping layer for `read_notifications`: sensitive-package
 * filtering, ongoing exclusion, newest-first ordering, caps, and truncation.
 * Everything that must hold BEFORE untrusted notification text reaches the
 * model lives here, testable on the JVM.
 */
object NotificationDigest {

    /** Hard ceiling regardless of what the model asks for. */
    const val MAX_LIMIT: Int = 30

    /**
     * Shared sensitive-package predicate — the SINGLE source of truth reused by both the MCP
     * `read_notifications` tool and the Live conversation-plane read tool. Delegates to the
     * internal [com.aura.mcp.server.SensitivePolicy] (same module) so banking/authenticator
     * notifications never reach any model surface.
     */
    fun isSensitivePackage(packageName: String): Boolean =
        com.aura.mcp.server.SensitivePolicy.screen(packageName) is com.aura.mcp.server.SensitivePolicy.Decision.Block

    const val TITLE_MAX_CHARS: Int = 120
    const val TEXT_MAX_CHARS: Int = 500

    fun select(
        snapshots: List<NotificationSnapshot>,
        includeOngoing: Boolean,
        packageFilter: String?,
        limit: Int,
        isBlocked: (String) -> Boolean,
    ): List<NotificationSnapshot> = snapshots.asSequence()
        .filterNot { isBlocked(it.packageName) }
        .filter { includeOngoing || !it.isOngoing }
        .filter { packageFilter == null || it.packageName == packageFilter }
        .sortedByDescending { it.postedAtMs }
        .take(limit.coerceIn(1, MAX_LIMIT))
        .toList()

    /** Truncate with a visible ellipsis so the model knows text was cut. */
    fun clip(text: String?, maxChars: Int): String? = when {
        text == null -> null
        text.length <= maxChars -> text
        else -> text.take(maxChars) + "…"
    }

    /** Case-insensitive, trimmed action-title match. */
    fun findAction(snapshot: NotificationSnapshot, title: String): NotificationActionSpec? =
        snapshot.actions.firstOrNull { it.title.trim().equals(title.trim(), ignoreCase = true) }
}
