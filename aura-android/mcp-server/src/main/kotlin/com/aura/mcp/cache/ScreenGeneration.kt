package com.aura.mcp.cache

import java.util.concurrent.atomic.AtomicLong

/**
 * P1: a process-wide monotonic counter of screen-mutating actions. Bumped by the
 * server chokepoint ([com.aura.mcp.server.scopedTool]) after every dispatched
 * WRITE-scoped tool call (gestures, launch_app, deep links, key presses…).
 *
 * Process-global on purpose — unlike the audit sinks, the SCREEN is genuinely
 * shared hardware state: a gesture fired by the in-process agent server also
 * invalidates the som_id map a WebRTC client perceived, and vice versa. Each
 * [PerceptionCache] snapshots the generation at update time and treats any
 * later bump as "the screen this cache describes may no longer exist".
 *
 * Keyed on WRITE **scope**, not a hand-maintained tool-name list, so a future
 * gesture tool (which defaults to WRITE fail-closed in `McpToolScopes`) dirties
 * perception automatically instead of silently escaping the invariant.
 */
object ScreenGeneration {
    private val counter = AtomicLong(0)

    val current: Long get() = counter.get()

    /** Record that a screen-mutating tool was dispatched (success or failure — a failed gesture may still have landed). */
    fun bump() {
        counter.incrementAndGet()
    }
}
