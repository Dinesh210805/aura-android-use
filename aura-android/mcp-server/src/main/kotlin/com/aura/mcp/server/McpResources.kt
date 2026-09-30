package com.aura.mcp.server

import com.aura.mcp.bridge.DeviceBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP **Resources** — read-only context an agent can pull on demand to ground
 * itself before acting. Unlike the `instructions` field (pushed once at
 * handshake) and tool descriptions (always visible), resources are fetched when
 * the model decides it needs them, so they hold the longer-form reference
 * material that would bloat every prompt if inlined.
 *
 * Three resources, each a stable `aura://` URI:
 *  - `aura://policy/sensitive-actions` — the live hard-block policy, so the
 *    agent knows its boundaries without trial-and-error.
 *  - `aura://guide/tool-selection` — the full behavioral contract / anti-wander
 *    runbook (same source as the handshake instructions, fetchable in full).
 *  - `aura://device/status` — a live device snapshot from [DeviceBridge].
 */
internal fun Server.registerResources(deviceBridge: DeviceBridge) {
    addResource(
        uri = "aura://policy/sensitive-actions",
        name = "Sensitive-action policy",
        description = "What the device hard-blocks and why. Read this to learn your safety boundaries " +
            "before attempting app launches, deep links, or text entry.",
        mimeType = "text/markdown",
    ) { request ->
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = SensitivePolicy.policyMarkdown(),
                    uri = request.uri,
                    mimeType = "text/markdown",
                ),
            ),
        )
    }

    addResource(
        uri = "aura://guide/tool-selection",
        name = "Tool-selection guide",
        description = "How to drive this device without wandering: the perceive→act→verify loop, " +
            "deep-link-first navigation, and the full behavioral contract.",
        mimeType = "text/markdown",
    ) { request ->
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = AuraInstructions.text,
                    uri = request.uri,
                    mimeType = "text/markdown",
                ),
            ),
        )
    }

    addResource(
        uri = "aura://device/status",
        name = "Live device status",
        description = "Current screen size, Android API level, device model, and accessibility-service state.",
        mimeType = "application/json",
    ) { request ->
        val status = deviceBridge.getDeviceStatus()
        val json = buildJsonObject {
            put("accessibility_service_running", status.accessibilityServiceRunning)
            put("screen_width_px", status.screenWidthPx)
            put("screen_height_px", status.screenHeightPx)
            put("android_api_level", status.androidApiLevel)
            put("device_model", status.deviceModel)
        }.toString()
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(text = json, uri = request.uri, mimeType = "application/json"),
            ),
        )
    }
}
