package com.aura.aura_ui.services.keepawake

import android.os.Handler
import android.os.Looper
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.bridge.ToolPhaseSink

/**
 * MCP-plane adapter for [ScreenAwakeController]: turns per-tool dispatch
 * events into ONE session-scoped keep-awake lease.
 *
 * Lifecycle (the "hotel key card" model — connection alone never holds the
 * screen):
 *  - **Acquire** on the first STARTED of a screen-needing tool. Pure-info
 *    tools (echo, web_search, lookup_app, …) never light the screen —
 *    a client listing tools or searching the web must not wake the phone.
 *  - **Hold across think-gaps:** every subsequent phase event slides the idle
 *    deadline forward, so the screen survives the 10–60 s the client spends
 *    thinking *between* calls — exactly where a per-call bracket would fail.
 *  - **Release** on whichever comes first: `end_session` completing (the
 *    client's explicit "run over"), [idleTimeoutMs] without any tool event
 *    (client crashed / Ctrl-C'd / walked away — MCP clients are not obligated
 *    to call end_session), or the controller's own 30-min backstop.
 *
 * Screen-needing = WRITE scope (gestures, launches — also the fail-closed
 * default for unclassified new tools, see McpToolScopes) plus the READ tools
 * that inspect the live display ([SCREEN_READ_TOOLS]).
 *
 * onPhase is called on the MCP request thread and must stay cheap: work here
 * is a set lookup, a monitor, and Handler re-scheduling (thread-safe).
 */
class KeepAwakeToolPhaseSink(
    private val controller: ScreenAwakeController,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) : ToolPhaseSink {

    private val lock = Any()
    private var lease: ScreenAwakeController.Lease? = null
    private val idleRelease = Runnable { release() }

    override fun onPhase(toolName: String, scope: McpScope, phase: ToolPhaseSink.Phase) {
        val needsScreen = scope == McpScope.WRITE || toolName in SCREEN_READ_TOOLS
        synchronized(lock) {
            if (phase == ToolPhaseSink.Phase.STARTED && needsScreen && lease == null) {
                lease = controller.acquire("mcp_session")
            }
            val held = lease ?: return
            handler.removeCallbacks(idleRelease)
            val sessionOver = toolName == END_SESSION_TOOL &&
                (phase == ToolPhaseSink.Phase.COMPLETED || phase == ToolPhaseSink.Phase.ERRORED)
            if (sessionOver) {
                lease = null
                held.close()
            } else {
                handler.postDelayed(idleRelease, idleTimeoutMs)
            }
        }
    }

    /** Idle-deadline / shutdown release. Safe when nothing is held. */
    fun release() {
        synchronized(lock) {
            handler.removeCallbacks(idleRelease)
            lease?.close()
            lease = null
        }
    }

    companion object {
        private const val END_SESSION_TOOL = "end_session"

        // Worst-case client think-time between two tool calls, with margin.
        // Shorter risks a mid-run dark screen; longer pins the display for
        // abandoned sessions. See docs discussion 2026-07-19.
        const val IDLE_TIMEOUT_MS = 3L * 60L * 1000L

        /**
         * READ-scope tools that inspect the live display and therefore need it
         * interactive. Everything else READ is screen-independent by
         * McpToolScopes' own classification rules.
         */
        val SCREEN_READ_TOOLS = setOf(
            "perceive_screen",
            "read_screen",
            "get_screenshot",
            "request_screen_capture_permission",
            "verify_action",
            "wait_for",
            "watch_device_events",
        )
    }
}
