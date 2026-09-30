package com.aura.mcp.tools

import android.util.Base64
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.ScreenshotBridge
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.aura.mcp.server.ResourceRequired
import com.aura.mcp.server.resourceRequiredResult

/**
 * Phase 9 — perception internals.
 *
 * The raw perception trio (`perceive_screen` / `get_annotated_screenshot` /
 * `omniparser_detect`) used to be registered as public MCP tools here. They
 * leaked implementation details and gave the calling AI three ways to do
 * roughly the same thing. They have been replaced by the single
 * [registerPerceiveScreenTool] façade, which uses [captureBytes] internally.
 *
 * This file is kept so the shared [captureBytes] helper has a stable home
 * inside the module without leaking out of `com.aura.mcp.tools`.
 */
internal suspend fun captureBytes(bridge: ScreenshotBridge): Pair<ByteArray?, CallToolResult?> {
    return when (val r = bridge.captureBase64Png()) {
        is CaptureResult.Success -> {
            val bytes = Base64.decode(r.base64Png, Base64.DEFAULT)
            bytes to null
        }
        is CaptureResult.Error -> null to errorResult(
            "Capture failed: ${r.message}. The screen was NOT read. Call perceive_screen " +
                "again — do NOT tap or act from a remembered/earlier screen; wait briefly " +
                "and re-perceive first.",
        )
        // Agent-fixable — see the same case in [PerceptionTools].
        CaptureResult.PermissionRequired -> null to resourceRequiredResult(
            toolName = "perceive_screen",
            resource = ResourceRequired.Resource.SCREEN_CAPTURE,
            fixableBy = ResourceRequired.Fixer.AGENT,
            message = "Screen capture has not been allowed yet, so the screen was NOT read.",
            hint = "You can fix this yourself: call request_screen_capture_permission, wait for " +
                "the user to accept the system dialog, then retry perceive_screen. Do not tap " +
                "or act from a remembered screen in the meantime.",
        )
    }
}
