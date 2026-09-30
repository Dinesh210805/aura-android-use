package com.aura.mcp.tools

import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.server.SensitivePolicy
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Protocol-shape tools that don't need the [com.aura.mcp.bridge.DeviceBridge]:
 *   validate_action, connect_device.
 *
 * - `validate_action`: pre-check a planned action against the on-device safety
 *   policy ([com.aura.mcp.server.SensitivePolicy]) before committing to it.
 *   This is the same gate the dispatcher enforces on every call, exposed so an
 *   agent can look before it leaps. Note the gate is enforced regardless — a
 *   blocked action is refused even if the agent skips this check.
 * - `connect_device`: on the Python side this brokers permission between a
 *   remote server and the Android device. We ARE the device, so this is
 *   trivially true. Kept as a tool so MCP clients can keep their existing
 *   call sequences working unchanged.
 */
internal fun Server.registerPolicyTools(lookupApp: ((String) -> AppLookupResult)? = null) {
    registerValidateAction(lookupApp)
    registerConnectDevice()
}

private fun Server.registerValidateAction(lookupApp: ((String) -> AppLookupResult)?) {
    scopedTool(
        name = "validate_action",
        description = "Check in advance whether an app, link or text would be blocked by the safety policy " +
            "(banking, payment and authenticator apps; card numbers, PINs, passwords). The same " +
            "checks run on every call anyway.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "gesture_type" to stringSchema("The action type (e.g. launch_app, open_deeplink, type_text)"),
                    "target" to stringSchema("The app name/package, deep-link URI, or text you plan to use"),
                ),
            ),
            required = listOf("gesture_type"),
        ),
    ) { request ->
        val gesture = request.arguments?.stringArg("gesture_type").orEmpty()
        val target = request.arguments?.stringArg("target").orEmpty()
        // Raw-string screen (package id / deep-link host / sensitive text patterns).
        // Name-blindness fix: a human app NAME ("Google Pay", "Microsoft Authenticator")
        // matches no package/host/text pattern, so the raw screen returns Allow even
        // though launch_app would block the RESOLVED package. When this is an app-
        // targeting gesture, resolve the name the same way launch_app does and screen
        // the resolved package, so the advisory verdict matches enforcement.
        val decision = SensitivePolicy.screen(target).let { raw ->
            if (raw is SensitivePolicy.Decision.Block) raw
            else resolveAndScreenAppName(gesture, target, lookupApp)
        }
        val payload = buildJsonObject {
            when (decision) {
                is SensitivePolicy.Decision.Block -> {
                    put("allowed", false)
                    put("category", decision.category.id)
                    put("reason", decision.message)
                }
                SensitivePolicy.Decision.Allow -> {
                    put("allowed", true)
                    put("category", "")
                    put("reason", "")
                }
            }
            put("gesture_type", gesture)
            put("target", target)
            put("policy_engine", "sensitive-policy-v1")
        }
        CallToolResult(content = listOf(TextContent(payload.toString())), isError = false)
    }
}

/**
 * For app-launch gestures, resolve a human app name to its installed package and screen
 * THAT (the raw name never matches a package pattern). Only for launch_app/open_deeplink —
 * a `type_text` target must not be misread as an app name. Fail-open on lookup miss/absent
 * bridge: the enforcing gate at launch_app is the real boundary; this only sharpens the
 * advisory verdict. Returns [SensitivePolicy.Decision.Allow] when nothing resolves.
 */
internal fun resolveAndScreenAppName(
    gesture: String,
    target: String,
    lookupApp: ((String) -> AppLookupResult)?,
): SensitivePolicy.Decision {
    if (lookupApp == null || target.isBlank()) return SensitivePolicy.Decision.Allow
    val g = gesture.trim().lowercase()
    if (g != "launch_app" && g != "open_deeplink") return SensitivePolicy.Decision.Allow
    val resolved = runCatching { lookupApp(target) }.getOrNull() ?: return SensitivePolicy.Decision.Allow
    if (!resolved.found || resolved.packageName.isBlank()) return SensitivePolicy.Decision.Allow
    return SensitivePolicy.screenPackage(resolved.packageName)
}

private fun Server.registerConnectDevice() {
    scopedTool(
        name = "connect_device",
        description = "Confirm device connectivity. On-device build always returns success — " +
            "the MCP server runs inside the AURA Android app itself.",
    ) { _ ->
        val payload = buildJsonObject {
            put("success", true)
            put("message", "On-device MCP server. Always connected.")
            put("transport", "in-process")
        }
        CallToolResult(content = listOf(TextContent(payload.toString())))
    }
}
