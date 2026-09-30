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
 * Phase 2 — first five real device-control tools, backed by [DeviceBridge].
 *
 * Each tool follows the same shape:
 *   1. Parse args from `request.arguments` (already a [JsonObject]).
 *   2. Call the bridge.
 *   3. Wrap the result in a [CallToolResult].
 */
internal fun Server.registerDeviceTools(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    registerTap(bridge, perceptionCache)
    registerPressHome(bridge)
    registerPressBack(bridge)
    registerVolumeTools(bridge)
    registerGetDeviceStatus(bridge)
}

// ── tap ─────────────────────────────────────────────────────────────────────

private fun Server.registerTap(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    scopedTool(
        name = "tap",
        description = "Tap an element by its som_id from the latest screen you were shown. The server turns it " +
            "into exact coordinates.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "som_id" to numberSchema("Element som_id from your latest look"),
                )
            ),
            required = listOf("som_id"),
        ),
    ) { request ->
        val somId = request.arguments?.intArg("som_id")
        if (somId == null) {
            return@scopedTool errorResult("tap requires a numeric 'som_id' from the latest screen")
        }
        val (x, y) = when (val res = perceptionCache.resolveForGesture(somId)) {
            is SomResolveOutcome.Error -> return@scopedTool res.result
            is SomResolveOutcome.Coords -> res.x to res.y
        }
        // The element's label rides the result so the caller can NAME what was touched —
        // the on-screen status strip says "Tapping Playlists" rather than "tap som_id 12",
        // and the model gets the same confirmation of what it actually hit. Omitted rather
        // than guessed when the element carried no label.
        val label = perceptionCache.labelFor(somId)
        jsonOk(
            bridge.performTap(x, y),
            buildMap {
                put("action", "tap")
                put("som_id", somId.toString())
                put("x", x.toString())
                put("y", y.toString())
                if (label != null) put("label", label)
            },
        )
    }
}

// ── press_home / press_back ─────────────────────────────────────────────────

private fun Server.registerPressHome(bridge: DeviceBridge) {
    scopedTool(
        name = "press_home",
        description = "Go to the home screen.",
    ) { _ ->
        jsonOk(bridge.performHome(), mapOf("action" to "home"))
    }
}

private fun Server.registerPressBack(bridge: DeviceBridge) {
    scopedTool(
        name = "press_back",
        description = "Go back one screen.",
    ) { _ ->
        jsonOk(bridge.performBack(), mapOf("action" to "back"))
    }
}

// ── volume_up / volume_down / mute ──────────────────────────────────────────

private fun Server.registerVolumeTools(bridge: DeviceBridge) {
    scopedTool(
        name = "volume_up",
        description = "Raise the media stream volume one notch.",
    ) { _ ->
        jsonOk(bridge.adjustVolume(VolumeDirection.UP), mapOf("action" to "volume_up"))
    }
    scopedTool(
        name = "volume_down",
        description = "Lower the media stream volume one notch.",
    ) { _ ->
        jsonOk(bridge.adjustVolume(VolumeDirection.DOWN), mapOf("action" to "volume_down"))
    }
    scopedTool(
        name = "mute",
        description = "Toggle mute on the media stream.",
    ) { _ ->
        jsonOk(bridge.adjustVolume(VolumeDirection.MUTE), mapOf("action" to "mute"))
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
