package com.aura.mcp.tools

import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.server.ResourceRequired
import com.aura.mcp.server.resourceRequiredResult
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Phase 4 — perception tools.
 *   get_screenshot, request_screen_capture_permission, watch_device_events.
 *
 * `get_ui_tree` used to live here and was retired 2026-08-18, superseded by
 * `read_screen` ([registerReadScreenTool]). It returned the raw tree payload — every
 * key name repeated per element, ~19k tokens for one Amazon screen — which made it
 * cheap in latency and unusable in practice. `read_screen` returns the same
 * information positionally at ~0.7k, settles the screen first, and grounds som_ids.
 */
internal fun Server.registerPerceptionTools(
    screenshotBridge: ScreenshotBridge,
    uiTreeBridge: UiTreeBridge,
) {
    registerGetScreenshot(screenshotBridge)
    registerRequestScreenCapturePermission(screenshotBridge)
    registerWatchDeviceEvents(uiTreeBridge)
}

private fun Server.registerGetScreenshot(bridge: ScreenshotBridge) {
    scopedTool(
        name = "get_screenshot",
        description = "Take a plain screenshot, with no boxes. For reading visual content only — to target an " +
            "element use read_screen or perceive_screen. If it returns permission_required, call " +
            "request_screen_capture_permission.",
    ) { _ ->
        when (val result = bridge.captureBase64Png()) {
            is CaptureResult.Success -> CallToolResult(
                content = listOf(
                    ImageContent(data = result.base64Png, mimeType = "image/png"),
                    TextContent(
                        buildJsonObject {
                            put("success", true)
                            put("width_px", result.widthPx)
                            put("height_px", result.heightPx)
                            put("mime_type", "image/png")
                        }.toString()
                    ),
                ),
            )
            is CaptureResult.Error -> errorResult("get_screenshot failed: ${result.message}")
            // Agent-fixable, and the reason `get_screenshot` stays registered without the
            // permission: the tool that grants it is right here in the same action space.
            CaptureResult.PermissionRequired -> resourceRequiredResult(
                toolName = "get_screenshot",
                resource = ResourceRequired.Resource.SCREEN_CAPTURE,
                fixableBy = ResourceRequired.Fixer.AGENT,
                message = "Screen capture has not been allowed yet, so no screenshot was taken.",
                hint = "You can fix this yourself: call request_screen_capture_permission, wait " +
                    "for the user to accept the system dialog, then retry get_screenshot.",
            )
        }
    }
}

private fun Server.registerRequestScreenCapturePermission(bridge: ScreenshotBridge) {
    scopedTool(
        name = "request_screen_capture_permission",
        description = "Show the system dialog that lets AURA capture the screen. The user must tap Allow.",
    ) { _ ->
        val alreadyReady = bridge.isReady()
        val dispatched = if (alreadyReady) true else bridge.requestPermission()
        val payload = buildJsonObject {
            put("triggered", dispatched)
            put("already_granted", alreadyReady)
            put(
                "message",
                if (alreadyReady) "Permission already granted"
                else if (dispatched) "Consent dialog launched — accept on device"
                else "Failed to dispatch permission request",
            )
        }
        CallToolResult(
            content = listOf(TextContent(payload.toString())),
            isError = !dispatched,
        )
    }
}

private fun Server.registerWatchDeviceEvents(bridge: UiTreeBridge) {
    scopedTool(
        name = "watch_device_events",
        description = "Block for up to timeout_seconds collecting accessibility events " +
            "(window/content/focus changes). Returns the batch when it fills or the timeout " +
            "elapses, whichever comes first. Defaults: timeout_seconds=10, max_events=50.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "timeout_seconds" to numberSchema("Max seconds to wait (default 10)"),
                    "max_events" to numberSchema("Max events to return (default 50)"),
                ),
            ),
        ),
    ) { request ->
        val args = request.arguments
        val timeoutSec = args?.intArg("timeout_seconds") ?: 10
        val maxEvents = args?.intArg("max_events") ?: 50
        val events = bridge.drainEvents(
            timeoutMs = (timeoutSec.coerceIn(1, 60) * 1000L),
            maxEvents = maxEvents.coerceIn(1, 200),
        )
        val payload = buildJsonObject {
            put("count", events.size)
            putJsonArray("events") {
                for (e in events) {
                    add(buildJsonObject {
                        put("type", e.type)
                        put("package_name", e.packageName)
                        put("timestamp_ms", e.timestampMs)
                        put("description", e.description)
                    })
                }
            }
        }
        CallToolResult(content = listOf(TextContent(payload.toString())))
    }
}
