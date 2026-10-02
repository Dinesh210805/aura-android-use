package com.aura.mcp.tools

import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

/**
 * Key presses: `press_key` for system navigation keys, `press_enter` for the focused field.
 *
 * - Why two tools: `press_key` navigates OUT of the current app and stays allowed while a
 *   banking app is foreground (the agent must be able to leave), while `press_enter` types
 *   INTO it and is denied there. `ForegroundGuard` gates by tool name, so the two cannot share one.
 * - Contract: the result's `action` names the key (`back`, `home`, `open_recent_apps`,
 *   `press_enter`); the status strip and run logs read it.
 */
internal fun Server.registerKeyTools(bridge: DeviceBridge) {
    scopedTool(
        name = "press_key",
        description = "Press a system key: back (one screen back), home (home screen), or recents " +
            "(recent-apps switcher).",
        inputSchema = ToolSchema(
            properties = JsonObject(mapOf("key" to stringSchema("back | home | recents"))),
            required = listOf("key"),
        ),
    ) { request ->
        when (request.arguments?.stringArg("key")?.trim()?.lowercase()) {
            "back" -> jsonOk(bridge.performBack(), mapOf("action" to "back"))
            "home" -> jsonOk(bridge.performHome(), mapOf("action" to "home"))
            "recents" -> jsonOk(bridge.performRecents(), mapOf("action" to "open_recent_apps"))
            else -> errorResult("press_key needs key: back, home or recents")
        }
    }

    scopedTool(
        name = "press_enter",
        description = "Press the keyboard's Enter or action key on the focused field. Use it to submit a search " +
            "box or address bar. For suggestion fields, tap the suggestion instead.",
    ) { _ ->
        jsonOk(bridge.pressEnter(), mapOf("action" to "press_enter"))
    }
}
