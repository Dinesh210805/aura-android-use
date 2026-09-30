package com.aura.mcp.tools

import com.aura.mcp.bridge.MediaBridge
import com.aura.mcp.bridge.MediaCommand
import com.aura.mcp.bridge.SpecialAccessState
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Media plane — cross-app playback control via `MediaSessionManager`. One
 * transport-control call replaces launch→perceive→find-button→tap, works with
 * the screen off, and cannot mis-tap. Gated behind the same "Notification
 * access" grant as the notification tools.
 */
internal fun Server.registerMediaTools(bridge: MediaBridge) {
    registerGetMediaSessions(bridge)
    registerMediaControl(bridge)
}

private fun mediaAccessDisabledResult(toolName: String): CallToolResult = jsonOkPayload(
    buildJsonObject {
        put("success", false)
        put("error", "notification_access_disabled")
        put("tool", toolName)
        put(
            "hint",
            "Media control needs the same 'Notification access' grant as the notification tools. " +
                "Ask the user to enable it: Settings → Notifications → Device & app notifications → AURA.",
        )
    },
    success = false,
)

private fun Server.registerGetMediaSessions(bridge: MediaBridge) {
    scopedTool(
        name = "get_media_sessions",
        description = "What is playing or paused: app, state, track, artist, position.",
        inputSchema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList()),
    ) { _ ->
        if (bridge.accessState() == SpecialAccessState.DISABLED) {
            return@scopedTool mediaAccessDisabledResult("get_media_sessions")
        }
        val sessions = bridge.activeSessions()
        jsonOkPayload(
            buildJsonObject {
                put("success", true)
                put("count", sessions.size)
                putJsonArray("sessions") {
                    sessions.forEach { s ->
                        add(
                            buildJsonObject {
                                put("package_name", s.packageName)
                                put("app_name", s.appName)
                                put("playback_state", s.playbackState)
                                s.title?.let { put("title", it) }
                                s.artist?.let { put("artist", it) }
                                s.positionMs?.let { put("position_ms", it) }
                                s.durationMs?.let { put("duration_ms", it) }
                            },
                        )
                    }
                }
                if (sessions.isEmpty()) {
                    put("hint", "No active media sessions — nothing is playing or recently paused.")
                }
            },
        )
    }
}

private fun Server.registerMediaControl(bridge: MediaBridge) {
    scopedTool(
        name = "media_control",
        description = "Play, pause, skip or stop media in any app without touching the screen: play, pause, " +
            "play_pause, next, previous, stop. Targets the most recent player; pass package_name " +
            "(from get_media_sessions) when several are playing.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "command" to stringSchema("One of: play, pause, play_pause, next, previous, stop"),
                    "package_name" to stringSchema("Target app package (optional — defaults to the active session)"),
                ),
            ),
            required = listOf("command"),
        ),
    ) { request ->
        if (bridge.accessState() == SpecialAccessState.DISABLED) {
            return@scopedTool mediaAccessDisabledResult("media_control")
        }
        val raw = request.arguments?.stringArg("command")
        val command = MediaCommand.parse(raw)
            ?: return@scopedTool errorResult(
                "Unknown media command '${raw ?: ""}'. Valid commands: " +
                    MediaCommand.entries.joinToString(", ") { it.name.lowercase() } + ".",
            )
        val packageName = request.arguments?.stringArg("package_name")?.trim()?.takeIf { it.isNotEmpty() }
        val result = bridge.sendCommand(packageName, command)
        jsonOkPayload(
            buildJsonObject {
                put("success", result.success)
                put("command", command.name.lowercase())
                result.targetPackage?.let { put("target_package", it) }
                result.error?.let { put("error", it) }
            },
            success = result.success,
        )
    }
}
