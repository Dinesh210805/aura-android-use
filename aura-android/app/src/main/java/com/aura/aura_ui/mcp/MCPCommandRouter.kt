package com.aura.aura_ui.mcp

import android.content.Context
import android.os.Build
import android.util.Log
import com.aura.aura_ui.accessibility.AccessibilityShot
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.accessibility.ScreenCapturePermissionActivity
import com.aura.aura_ui.data.Command
import com.aura.aura_ui.executor.CommandExecutor
import com.aura.aura_ui.network.ConnectionManager
import com.aura.aura_ui.overlay.AuraOverlayService
import com.aura.aura_ui.overlay.VisualFeedbackHandler
import com.aura.aura_ui.utils.ContactResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.aura.aura_ui.services.startActivityAsAura

/**
 * Central command router for all MCP server messages received via WebSocket.
 *
 * Consolidates command handling that was previously split between
 * [com.aura.aura_ui.services.AssistantForegroundService] and
 * [com.aura.aura_ui.voice.VoiceCaptureController] into a single,
 * lifecycle-independent dispatcher.
 *
 * This router is owned by [AssistantForegroundService] and operates via the
 * persistent [ConnectionManager] WebSocket at `/ws/device`. It does **not**
 * depend on the overlay or VoiceCaptureController being active — all device
 * control commands work in background keep-alive mode.
 *
 * For UI-display commands (task_progress, agent_status, hitl), optional
 * callbacks are provided that the overlay can subscribe to when visible.
 */
class MCPCommandRouter(
    private val context: Context,
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "MCPCommandRouter"
    }

    /**
     * Optional callback for UI-display messages that require the overlay.
     *
     * **This currently has no subscriber** (REVIEW_LOG OV1). Its only assignment
     * lived in `AssistantForegroundService.handleStartOverlay()`, which drove the
     * legacy `FloatingMicOverlay` stack — unreachable, and deleted 2026-08-16. The
     * KDoc previously claimed `AuraOverlayService` / `VoiceCaptureController` set
     * it; neither ever did.
     *
     * Consequence: of the five types routed to it below, only `VISUAL_FEEDBACK`
     * survives (handled directly via [VisualFeedbackHandler]). `TASK_PROGRESS`,
     * `AGENT_STATUS`, `HITL_QUESTION` and `HITL_DISMISS` arriving over the MCP
     * plane reach this null and stop — so an MCP-plane HITL question is never
     * shown and its caller waits forever. Kept rather than deleted so re-wiring it
     * to [AuraOverlayService] / [HITLHandler] stays a one-line change.
     */
    var onUIMessage: ((type: String, json: JSONObject) -> Unit)? = null

    /**
     * Route an incoming WebSocket message to the appropriate handler.
     *
     * @param text raw JSON string received from MCP server
     * @return true if the message was handled, false if unrecognized
     */
    fun dispatch(text: String): Boolean {
        return try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                // ── Device Control (works in background) ────────────────────

                MCPProtocol.EXECUTE_GESTURE -> {
                    val gesture = json.optJSONObject("gesture")
                    val commandId = gesture?.optString("command_id", "")
                    Log.i(TAG, "⚡ Gesture received: commandId=$commandId")
                    if (gesture != null) {
                        handleExecuteGesture(gesture, commandId)
                    }
                    true
                }

                MCPProtocol.REQUEST_SCREENSHOT -> {
                    val requestId = json.optString("request_id", "")
                    Log.i(TAG, "📸 Screenshot request: requestId=$requestId")
                    handleRequestScreenshot(requestId)
                    true
                }

                MCPProtocol.REQUEST_UI_TREE -> {
                    val requestId = json.optString("request_id", "")
                    Log.i(TAG, "📋 UI tree request: requestId=$requestId")
                    handleRequestUiTree(requestId)
                    true
                }

                MCPProtocol.LAUNCH_APP -> {
                    val packageName = json.optString("package_name", "")
                    val packageCandidates = json.optJSONArray("package_candidates")
                    val commandId = json.optString("command_id", "")
                    Log.i(TAG, "🚀 Launch app: package=$packageName, commandId=$commandId")
                    handleLaunchApp(packageName, packageCandidates, commandId)
                    true
                }

                MCPProtocol.LAUNCH_DEEP_LINK -> {
                    val uri = json.optString("uri", "")
                    val packageName = json.optString("package_name", "").ifEmpty { null }
                    val commandId = json.optString("command_id", "")
                    Log.i(TAG, "🔗 Launch deep link: uri=$uri, commandId=$commandId")
                    handleLaunchDeepLink(uri, packageName, commandId)
                    true
                }

                MCPProtocol.RESOLVE_CONTACT -> {
                    val contactName = json.optString("contact_name", "")
                    val requestId = json.optString("request_id", "")
                    Log.i(TAG, "📞 Contact resolution: $contactName, requestId=$requestId")
                    handleResolveContact(contactName, requestId)
                    true
                }

                MCPProtocol.REQUEST_SCREEN_CAPTURE_PERMISSION -> {
                    Log.i(TAG, "📸 Screen capture permission request")
                    handleRequestScreenCapturePermission()
                    true
                }

                MCPProtocol.CONNECTION_REQUEST -> {
                    Log.i(TAG, "🔗 Connection request from MCP server")
                    // Delegate to AssistantForegroundService for notification
                    false // Let the caller handle this (needs notification APIs)
                }

                MCPProtocol.EXECUTE_STEP -> {
                    val stepId = json.optString("step_id", "")
                    val action = json.optJSONObject("action")
                    Log.i(TAG, "⚡ Step execution: $stepId")
                    handleExecuteStep(stepId, action)
                    true
                }

                // ── UI Display (forwarded to overlay when visible) ──────────

                MCPProtocol.TASK_PROGRESS,
                MCPProtocol.AGENT_STATUS,
                MCPProtocol.VISUAL_FEEDBACK,
                MCPProtocol.HITL_QUESTION,
                MCPProtocol.HITL_DISMISS -> {
                    Log.d(TAG, "📱 UI message: $type")
                    // Handle visual feedback directly (doesn't need overlay ViewModel)
                    if (type == MCPProtocol.VISUAL_FEEDBACK) {
                        VisualFeedbackHandler.handleMessage(json)
                    }
                    // Forward to overlay if active
                    onUIMessage?.invoke(type, json)
                    true
                }

                // ── Server acknowledgments (informational) ──────────────────

                MCPProtocol.DEVICE_INFO_ACK -> {
                    Log.i(TAG, "✅ Device info acknowledged by server")
                    true
                }

                else -> {
                    Log.d(TAG, "⚠️ Unhandled MCP message type: $type")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch MCP message: $text", e)
            false
        }
    }

    // ── Gesture Handling ─────────────────────────────────────────────────────

    private fun handleExecuteGesture(gesture: JSONObject, commandId: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                // Minimize overlay during gesture execution
                withContext(Dispatchers.Main) {
                    AuraOverlayService.minimize(context)
                }

                val service = AuraAccessibilityService.instance
                if (service == null) {
                    Log.e(TAG, "❌ AccessibilityService not available for gesture")
                    commandId?.let { send(MCPProtocol.buildGestureAck(it, false, "AccessibilityService not available")) }
                    return@launch
                }

                val action = gesture.getString("action")
                Log.i(TAG, "⚡ Executing gesture: $action, commandId=$commandId")

                when (action.lowercase()) {
                    "tap", "click" -> {
                        val x = gesture.optInt("x", -1)
                        val y = gesture.optInt("y", -1)
                        if (x >= 0 && y >= 0) {
                            VisualFeedbackHandler.showTapAt(x, y)
                            service.performTap(x, y, commandId)
                            Log.i(TAG, "✅ Tap dispatched at ($x, $y)")
                        } else {
                            Log.e(TAG, "❌ Invalid tap coordinates: x=$x, y=$y")
                            commandId?.let { send(MCPProtocol.buildGestureAck(it, false, "Invalid coordinates: x=$x, y=$y")) }
                        }
                    }
                    "swipe" -> {
                        val x1 = gesture.optInt("x1", -1)
                        val y1 = gesture.optInt("y1", -1)
                        val x2 = gesture.optInt("x2", -1)
                        val y2 = gesture.optInt("y2", -1)
                        val duration = gesture.optLong("duration", 300L)
                        if (x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) {
                            service.performSwipe(x1, y1, x2, y2, duration, commandId)
                            Log.i(TAG, "✅ Swipe dispatched from ($x1, $y1) to ($x2, $y2)")
                        } else {
                            Log.e(TAG, "❌ Invalid swipe coordinates")
                            commandId?.let { send(MCPProtocol.buildGestureAck(it, false, "Invalid swipe coordinates")) }
                        }
                    }
                    "long_press" -> {
                        val x = gesture.optInt("x", -1)
                        val y = gesture.optInt("y", -1)
                        val duration = gesture.optLong("duration", 1000L)
                        if (x >= 0 && y >= 0) {
                            service.performLongPress(x, y, duration, commandId)
                            Log.i(TAG, "✅ Long press dispatched at ($x, $y)")
                        } else {
                            Log.e(TAG, "❌ Invalid long press coordinates: x=$x, y=$y")
                            commandId?.let { send(MCPProtocol.buildGestureAck(it, false, "Invalid coordinates: x=$x, y=$y")) }
                        }
                    }
                    "type", "input", "type_text", "text_input" -> {
                        val text = gesture.optString("text", "")
                        if (text.isNotEmpty()) {
                            val focusX = gesture.optInt("focus_x", -1)
                            val focusY = gesture.optInt("focus_y", -1)
                            val success = service.performTextInput(text, focusX, focusY)
                            Log.i(TAG, "✅ Text input: '$text', success=$success")
                            commandId?.let { send(MCPProtocol.buildGestureAck(it, success, if (success) null else "Text input failed")) }
                        } else {
                            Log.e(TAG, "❌ Empty text for type action")
                            commandId?.let { send(MCPProtocol.buildGestureAck(it, false, "Empty text provided")) }
                        }
                    }
                    "scroll_up" -> {
                        service.performScroll("up", commandId)
                        Log.i(TAG, "✅ Scroll up dispatched")
                    }
                    "scroll_down" -> {
                        service.performScroll("down", commandId)
                        Log.i(TAG, "✅ Scroll down dispatched")
                    }
                    "back" -> {
                        service.performBack()
                        commandId?.let { send(MCPProtocol.buildGestureAck(it, true)) }
                    }
                    "home" -> {
                        service.performHome()
                        commandId?.let { send(MCPProtocol.buildGestureAck(it, true)) }
                    }
                    "recents", "recent_apps" -> {
                        service.performRecents()
                        commandId?.let { send(MCPProtocol.buildGestureAck(it, true)) }
                    }
                    "dismiss_keyboard" -> {
                        service.dismissKeyboard()
                        commandId?.let { send(MCPProtocol.buildGestureAck(it, true)) }
                    }
                    "restore_keyboard" -> {
                        service.restoreKeyboard()
                        commandId?.let { send(MCPProtocol.buildGestureAck(it, true)) }
                    }
                    "press_enter" -> {
                        val success = service.performEnterAction()
                        commandId?.let { send(MCPProtocol.buildGestureAck(it, success, if (success) null else "Enter action failed")) }
                    }
                    "press_search" -> {
                        service.pressKeyEvent(android.view.KeyEvent.KEYCODE_SEARCH)
                        commandId?.let { send(MCPProtocol.buildGestureAck(it, true)) }
                    }
                    else -> {
                        // System actions (wifi, bluetooth, volume, etc.)
                        service.executeSystemAction(action, commandId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error executing gesture: ${e.message}", e)
                commandId?.let { send(MCPProtocol.buildGestureAck(it, false, e.message)) }
            }
        }
    }

    // ── Perception Handling ──────────────────────────────────────────────────

    private fun handleRequestScreenshot(requestId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val service = AuraAccessibilityService.instance
                if (service == null) {
                    Log.w(TAG, "AccessibilityService not available for screenshot")
                    sendScreenshotResponse(requestId, null)
                    return@launch
                }

                // Routed through the service's single capture entry point — never MediaProjection.
                // The old MediaProjection precondition ("call request_screen_capture_permission
                // first") is gone because there is no permission to request: takeScreenshot()
                // needs no consent and holds nothing between calls.
                when (val shot = service.captureScreenshot()) {
                    is AccessibilityShot.Ok -> {
                        send(MCPProtocol.buildScreenshotResponse(
                            requestId,
                            shot.base64Jpeg,
                            shot.widthPx,
                            shot.heightPx,
                        ))
                        Log.i(TAG, "📸 Screenshot response sent: ${shot.base64Jpeg.length} chars")
                    }
                    is AccessibilityShot.Failed -> {
                        Log.w(TAG, "📸 Cannot capture screenshot: ${shot.message}")
                        send(
                            MCPProtocol.buildScreenshotResponse(
                                requestId,
                                "",
                                service.getScreenWidth(),
                                service.getScreenHeight(),
                            ).apply { put("error", shot.message) },
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screenshot: ${e.message}")
                sendScreenshotResponse(requestId, null)
            }
        }
    }

    private fun sendScreenshotResponse(requestId: String, screenshot: String?) {
        val service = AuraAccessibilityService.instance
        val screenWidth = service?.getScreenWidth() ?: 1080
        val screenHeight = service?.getScreenHeight() ?: 1920
        send(MCPProtocol.buildScreenshotResponse(requestId, screenshot ?: "", screenWidth, screenHeight))
    }

    private fun handleRequestUiTree(requestId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val service = AuraAccessibilityService.instance
                if (service == null) {
                    Log.w(TAG, "AccessibilityService not available for UI tree")
                    sendUiTreeResponse(requestId, null)
                    return@launch
                }

                val uiTree = service.getUITree()
                sendUiTreeResponse(requestId, uiTree)
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing UI tree: ${e.message}")
                sendUiTreeResponse(requestId, null)
            }
        }
    }

    private fun sendUiTreeResponse(requestId: String, uiTree: Map<String, Any>?) {
        val service = AuraAccessibilityService.instance
        val screenWidth = service?.getScreenWidth() ?: 1080
        val screenHeight = service?.getScreenHeight() ?: 1920

        val uiTreeObj = if (uiTree != null) {
            JSONObject(uiTree).apply {
                put("screen_width", screenWidth)
                put("screen_height", screenHeight)
            }
        } else {
            JSONObject().apply {
                put("elements", org.json.JSONArray())
                put("screen_width", screenWidth)
                put("screen_height", screenHeight)
                put("orientation", "portrait")
                put("timestamp", System.currentTimeMillis())
            }
        }

        send(MCPProtocol.buildUiTreeResponse(requestId, uiTreeObj))
        Log.i(TAG, "📤 UI tree response sent: requestId=$requestId, screen=${screenWidth}x${screenHeight}")
    }

    // ── App Launch Handling ──────────────────────────────────────────────────

    private fun handleLaunchApp(
        packageName: String,
        packageCandidates: org.json.JSONArray?,
        commandId: String,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                // Minimize overlay during automation
                withContext(Dispatchers.Main) {
                    AuraOverlayService.minimize(context)
                }

                val executor = CommandExecutor(context)

                // Build candidate list
                val candidates = mutableListOf<String>()
                if (packageName.isNotEmpty()) candidates.add(packageName)
                if (packageCandidates != null) {
                    for (i in 0 until packageCandidates.length()) {
                        val candidate = packageCandidates.optString(i, "")
                        if (candidate.isNotEmpty() && !candidates.contains(candidate)) {
                            candidates.add(candidate)
                        }
                    }
                }

                var success = false
                var error: String? = null

                for (pkg in candidates) {
                    val result = executor.executeCommand(
                        Command(
                            commandId = commandId,
                            commandType = "launch_app",
                            payload = mapOf("package_name" to pkg),
                            createdAt = System.currentTimeMillis().toString(),
                        )
                    )
                    if (result.success) {
                        success = true
                        Log.i(TAG, "✅ App launched: $pkg")
                        break
                    } else {
                        error = result.error
                        Log.w(TAG, "⚠️ Failed to launch $pkg: ${result.error}")
                    }
                }

                send(MCPProtocol.buildCommandResult(commandId, "launch_app", success, error))
            } catch (e: Exception) {
                Log.e(TAG, "Error launching app: ${e.message}")
                send(MCPProtocol.buildCommandResult(commandId, "launch_app", false, e.message))
            }
        }
    }

    private fun handleLaunchDeepLink(uri: String, packageName: String?, commandId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val executor = CommandExecutor(context)
                val payload = mutableMapOf<String, Any?>("uri" to uri)
                if (packageName != null) payload["package_name"] = packageName

                val result = executor.executeCommand(
                    Command(
                        commandId = commandId,
                        commandType = "launch_deep_link",
                        payload = payload,
                        createdAt = System.currentTimeMillis().toString(),
                    )
                )

                send(MCPProtocol.buildCommandResult(commandId, "launch_deep_link", result.success, result.error))
            } catch (e: Exception) {
                Log.e(TAG, "Error launching deep link: ${e.message}")
                send(MCPProtocol.buildCommandResult(commandId, "launch_deep_link", false, e.message))
            }
        }
    }

    // ── Contact Resolution ───────────────────────────────────────────────────

    private fun handleResolveContact(contactName: String, requestId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val phoneNumber = ContactResolver.findPhoneNumber(context, contactName)

                if (phoneNumber != null) {
                    val cleaned = ContactResolver.cleanPhoneNumber(phoneNumber)
                    Log.i(TAG, "✅ Contact resolved: $contactName → $cleaned")
                    send(MCPProtocol.buildContactResolutionResult(requestId, contactName, cleaned, true))
                } else {
                    Log.w(TAG, "⚠️ Contact not found: $contactName")
                    send(MCPProtocol.buildContactResolutionResult(requestId, contactName, null, false, "Contact not found"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Contact resolution failed", e)
                send(MCPProtocol.buildContactResolutionResult(requestId, contactName, null, false, e.message))
            }
        }
    }

    // ── Screen Capture Permission ────────────────────────────────────────────

    private fun handleRequestScreenCapturePermission() {
        scope.launch(Dispatchers.Main) {
            try {
                val service = AuraAccessibilityService.instance
                if (service?.isMediaProjectionAvailable() == true) {
                    Log.i(TAG, "📸 Screen capture already available, notifying server")
                    AuraAccessibilityService.sendScreenCapturePermissionResult(granted = true)
                    return@launch
                }

                // Launch the permission activity
                val intent = ScreenCapturePermissionActivity.createIntent(context)
                context.startActivityAsAura(intent)
                Log.i(TAG, "📤 Started ScreenCapturePermissionActivity")
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting screen capture permission: ${e.message}", e)
            }
        }
    }

    // ── Step Execution (Legacy) ──────────────────────────────────────────────

    private fun handleExecuteStep(stepId: String, action: JSONObject?) {
        scope.launch(Dispatchers.IO) {
            try {
                if (action == null) {
                    send(MCPProtocol.buildStepResult(stepId, false, "No action provided"))
                    return@launch
                }

                val service = AuraAccessibilityService.instance
                if (service == null) {
                    send(MCPProtocol.buildStepResult(stepId, false, "AccessibilityService not available"))
                    return@launch
                }

                val actionType = action.optString("type", "")
                val success = executeStepAction(service, actionType, action)

                // Brief delay to let UI update
                kotlinx.coroutines.delay(300)

                val uiAfter = service.getUITree()
                send(MCPProtocol.buildStepResult(stepId, success, if (success) null else "Action failed", uiAfter))
            } catch (e: Exception) {
                Log.e(TAG, "Error executing step: ${e.message}")
                send(MCPProtocol.buildStepResult(stepId, false, e.message))
            }
        }
    }

    private fun executeStepAction(
        service: AuraAccessibilityService,
        actionType: String,
        action: JSONObject,
    ): Boolean {
        return when (actionType.lowercase()) {
            "tap", "click" -> {
                val x = action.optInt("x", -1)
                val y = action.optInt("y", -1)
                if (x >= 0 && y >= 0) { service.performClick(x, y); true } else false
            }
            "swipe" -> {
                val x1 = action.optInt("x1", action.optInt("startX", -1))
                val y1 = action.optInt("y1", action.optInt("startY", -1))
                val x2 = action.optInt("x2", action.optInt("endX", -1))
                val y2 = action.optInt("y2", action.optInt("endY", -1))
                val duration = action.optLong("duration", 300)
                if (x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) { service.performSwipe(x1, y1, x2, y2, duration); true } else false
            }
            "scroll_up" -> { service.performScroll("up"); true }
            "scroll_down" -> { service.performScroll("down"); true }
            "back" -> { service.performBack(); true }
            "home" -> { service.performHome(); true }
            "dismiss_keyboard" -> { service.dismissKeyboard(); true }
            "press_enter" -> service.performEnterAction()
            "press_search" -> { service.pressKeyEvent(android.view.KeyEvent.KEYCODE_SEARCH); true }
            "type", "text_input", "input" -> {
                val text = action.optString("text", "")
                if (text.isNotEmpty()) {
                    val focusX = action.optInt("focus_x", -1)
                    val focusY = action.optInt("focus_y", -1)
                    service.performTextInput(text, focusX, focusY)
                } else false
            }
            else -> false
        }
    }

    // ── Transport ────────────────────────────────────────────────────────────

    private fun send(json: JSONObject) {
        connectionManager.send(json.toString())
    }
}
