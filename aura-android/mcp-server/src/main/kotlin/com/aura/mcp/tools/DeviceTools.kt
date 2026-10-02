package com.aura.mcp.tools

import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.VolumeDirection
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Device-level tools backed by [DeviceBridge]: element taps, media volume and device status.
 *
 * - Contract: a result's `action` names the specific gesture (`double_tap`, `volume_up`, …),
 *   not the tool; the status strip and run logs read it.
 */
internal fun Server.registerDeviceTools(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    registerTap(bridge, perceptionCache)
    registerVolume(bridge)
    registerGetDeviceStatus(bridge)
}

// ── tap ─────────────────────────────────────────────────────────────────────

private fun Server.registerTap(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    scopedTool(
        name = "tap",
        description = "Tap an element by its som_id from the latest screen you were shown; the server turns " +
            "it into exact coordinates. style: single (default), double, or long (press and hold for " +
            "duration_ms, default 1000).",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "som_id" to numberSchema("Element som_id from your latest look"),
                    "style" to stringSchema("single | double | long (default single)"),
                    "duration_ms" to numberSchema("long only: hold time in ms (default 1000)"),
                )
            ),
            required = listOf("som_id"),
        ),
    ) { request ->
        val args = request.arguments
        val somId = args?.intArg("som_id")
            ?: return@scopedTool errorResult("tap requires a numeric 'som_id' from the latest screen")
        val style = args?.stringArg("style")?.trim()?.lowercase() ?: "single"
        if (style !in TAP_STYLES) {
            return@scopedTool errorResult("tap style must be single, double or long, not '$style'")
        }
        val (x, y) = when (val res = perceptionCache.resolveForGesture(somId)) {
            is SomResolveOutcome.Error -> return@scopedTool res.result
            is SomResolveOutcome.Coords -> res.x to res.y
        }
        val holdMs = args?.intArg("duration_ms") ?: 1000
        val ok = when (style) {
            "double" -> bridge.performDoubleTap(x, y)
            "long" -> bridge.performLongPress(x, y, holdMs.toLong())
            else -> bridge.performTap(x, y)
        }
        // The element's label rides the result so the caller can NAME what was touched — the
        // status strip says "Tapping Playlists" rather than "tap som_id 12". Omitted rather than
        // guessed when the element carried no label.
        val label = perceptionCache.labelFor(somId)
        jsonOk(
            ok,
            buildMap {
                put("action", TAP_ACTIONS.getValue(style))
                put("som_id", somId.toString())
                put("x", x.toString())
                put("y", y.toString())
                if (style == "long") put("duration_ms", holdMs.toString())
                if (label != null) put("label", label)
            },
        )
    }
}

private val TAP_STYLES = setOf("single", "double", "long")
private val TAP_ACTIONS = mapOf("single" to "tap", "double" to "double_tap", "long" to "long_press")

// ── volume ──────────────────────────────────────────────────────────────────

private fun Server.registerVolume(bridge: DeviceBridge) {
    scopedTool(
        name = "volume",
        description = "Change the media volume one notch: up, down, or mute (toggles).",
        inputSchema = ToolSchema(
            properties = JsonObject(mapOf("action" to stringSchema("up | down | mute"))),
            required = listOf("action"),
        ),
    ) { request ->
        val (direction, action) = when (request.arguments?.stringArg("action")?.trim()?.lowercase()) {
            "up" -> VolumeDirection.UP to "volume_up"
            "down" -> VolumeDirection.DOWN to "volume_down"
            "mute" -> VolumeDirection.MUTE to "mute"
            else -> return@scopedTool errorResult("volume action must be up, down or mute")
        }
        jsonOk(bridge.adjustVolume(direction), mapOf("action" to action))
    }
}

// ── get_device_status ───────────────────────────────────────────────────────

private fun Server.registerGetDeviceStatus(bridge: DeviceBridge) {
    scopedTool(
        name = "get_device_status",
        description = "Device facts: screen size, whether accessibility is on (null means off), and " +
            "foreground_package — the app on screen now.",
    ) { _ ->
        val s = bridge.getDeviceStatus()
        val payload = buildJsonObject {
            put("accessibility_service_running", s.accessibilityServiceRunning)
            put("screen_width_px", s.screenWidthPx)
            put("screen_height_px", s.screenHeightPx)
            put("android_api_level", s.androidApiLevel)
            put("device_model", s.deviceModel)
            put("foreground_package", s.foregroundPackage)
            put("foreground_activity", s.foregroundActivity)
        }
        CallToolResult(content = listOf(TextContent(payload.toString())))
    }
}
