package com.aura.mcp.tools

import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Phase 3 — keyboard / recents tools that don't take arguments:
 *   open_recent_apps, press_enter.
 */
internal fun Server.registerKeyTools(bridge: DeviceBridge) {
    scopedTool(
        name = "open_recent_apps",
        description = "Open the recent-apps switcher.",
    ) { _ ->
        jsonOk(bridge.performRecents(), mapOf("action" to "open_recent_apps"))
    }

    scopedTool(
        name = "press_enter",
        description = "Press the keyboard's Enter or action key on the focused field. Use it to submit a search " +
            "box or address bar. For suggestion fields, tap the suggestion instead.",
    ) { _ ->
        jsonOk(bridge.pressEnter(), mapOf("action" to "press_enter"))
    }
}
