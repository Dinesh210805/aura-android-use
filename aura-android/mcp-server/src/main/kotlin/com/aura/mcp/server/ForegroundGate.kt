package com.aura.mcp.server

import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.tools.UiTreeHeuristics

/**
 * GP5+GP6 — dispatch-time foreground check. Pure decision logic lives in
 * [ForegroundGuard]; this class only supplies the foreground package, and only
 * when the tool is actually gated (ungated tools never pay a tree read).
 *
 * Fail-open by construction: any snapshot failure yields a null foreground,
 * which [ForegroundGuard.evaluate] allows. See ForegroundGuard's KDoc for why.
 */
internal class ForegroundGate(
    private val foregroundPackage: suspend () -> String?,
) {
    /** Non-null = deny this call. */
    suspend fun check(toolName: String): ForegroundGuard.Verdict.Block? {
        if (ForegroundGuard.gatedKind(toolName) == null) return null
        val fg = runCatching { foregroundPackage() }.getOrNull()
        return ForegroundGuard.evaluate(toolName, fg) as? ForegroundGuard.Verdict.Block
    }

    companion object {
        /** Unit tests / non-device hosts: gate never blocks. */
        val NOOP = ForegroundGate(foregroundPackage = { null })

        /** Real wiring: foreground = root package of the live accessibility tree. */
        fun fromUiTree(bridge: UiTreeBridge): ForegroundGate = ForegroundGate(
            foregroundPackage = {
                val snap = bridge.snapshot()
                if (!snap.ok) {
                    null
                } else {
                    UiTreeHeuristics.summarize(snap.payloadJson).foregroundApp.ifBlank { null }
                }
            },
        )
    }
}
