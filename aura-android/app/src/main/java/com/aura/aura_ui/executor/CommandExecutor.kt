package com.aura.aura_ui.executor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.aura.aura_ui.MainActivity
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.accessibility.gesture.*
import com.aura.aura_ui.data.Command
import com.aura.aura_ui.data.CommandResult
import com.aura.aura_ui.utils.AgentLogger
import java.util.UUID

class CommandExecutor(private val context: Context) {

    suspend fun executeCommand(command: Command): CommandResult {
        return try {
            AgentLogger.Auto.i(
                "Executing command",
                mapOf("type" to command.commandType, "id" to command.commandId),
            )
            val payload = command.payload ?: emptyMap()
            when (command.commandType) {
                "launch_app" -> executeLaunchApp(payload)
                "launch_deep_link" -> executeLaunchDeepLink(payload)
                "gesture" -> executeGesture(payload)
                "capture_screenshot" -> executeCaptureScreenshot()
                "send_message" -> executeSendMessage()
                else -> {
                    AgentLogger.Auto.w("Unknown command type: ${command.commandType}")
                    CommandResult(success = false, error = "Unknown command type: ${command.commandType}")
                }
            }
        } catch (e: Exception) {
            AgentLogger.Auto.e("Command execution error", e)
            CommandResult(success = false, error = "Execution failed: ${e.message}")
        }
    }

    private fun executeLaunchApp(payload: Map<String, Any?>): CommandResult {
        val packageName = payload["package_name"] as? String
        if (packageName.isNullOrEmpty()) {
            return CommandResult(success = false, error = "Missing package_name in payload")
        }
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AgentLogger.Auto.i("Launched app: $packageName")
                CommandResult(success = true)
            } else {
                AgentLogger.Auto.w("App not found: $packageName")
                CommandResult(success = false, error = "App not found: $packageName")
            }
        } catch (e: Exception) {
            AgentLogger.Auto.e("Failed to launch app", e)
            CommandResult(success = false, error = "Launch failed: ${e.message}")
        }
    }

    private fun executeLaunchDeepLink(payload: Map<String, Any?>): CommandResult {
        val uri = payload["uri"] as? String
        val packageName = payload["package_name"] as? String
        if (uri.isNullOrEmpty()) {
            return CommandResult(success = false, error = "Missing uri in payload")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!packageName.isNullOrEmpty()) {
                intent.setPackage(packageName)
            }
            val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo != null) {
                context.startActivity(intent)
                AgentLogger.Auto.i("Launched deep link: $uri${if (packageName != null) " via $packageName" else ""}")
                CommandResult(success = true)
            } else {
                AgentLogger.Auto.w("No app can handle URI: $uri")
                CommandResult(success = false, error = "No app available to handle: $uri")
            }
        } catch (e: Exception) {
            AgentLogger.Auto.e("Failed to launch deep link", e)
            CommandResult(success = false, error = "Deep link launch failed: ${e.message}")
        }
    }

    private suspend fun executeGesture(payload: Map<String, Any?>): CommandResult {
        val service = AuraAccessibilityService.instance
            ?: return CommandResult(success = false, error = "AccessibilityService not available")

        val hasNewFormat = payload.containsKey("target") || payload.containsKey("gesture_type")
        if (hasNewFormat) {
            return executeNewGestureFormat(payload, service)
        }

        val action = payload["action"] as? String
            ?: return CommandResult(success = false, error = "Missing action in gesture payload")

        AgentLogger.Auto.i("Executing gesture (legacy): action=$action")
        return executeLegacyGesture(action, payload, service)
    }

    private suspend fun executeNewGestureFormat(
        payload: Map<String, Any?>,
        service: AuraAccessibilityService,
    ): CommandResult {
        val command = parseGestureCommand(payload)
            ?: return CommandResult(success = false, error = "Failed to parse gesture command")

        AgentLogger.Auto.i(
            "Executing gesture (new format)",
            mapOf("type" to command.gestureType.name, "id" to command.commandId),
        )

        return when (val result = service.gestureInjector.executeAndAwait(command)) {
            is GestureResult.Success -> CommandResult(success = true)
            is GestureResult.Failure -> CommandResult(success = false, error = "${result.error.name}: ${result.details ?: ""}")
            is GestureResult.Cancelled -> CommandResult(success = false, error = "Gesture cancelled")
        }
    }

    private fun parseGestureCommand(payload: Map<String, Any?>): GestureCommand? {
        return try {
            val commandId = (payload["command_id"] as? String) ?: "cmd_${UUID.randomUUID()}"
            val gestureType = when ((payload["gesture_type"] as? String)?.lowercase()) {
                "long_press" -> GestureType.LONG_PRESS
                "swipe" -> GestureType.SWIPE
                "scroll" -> GestureType.SCROLL
                else -> GestureType.TAP
            }
            val target = parseGestureTarget(payload["target"]) ?: return null
            val endTarget = (payload["end_target"] as? Map<*, *>)?.let { endMap ->
                @Suppress("UNCHECKED_CAST")
                parseCoordinatesTarget(endMap as Map<String, Any?>)
            }
            val options = parseGestureOptions(payload["options"] as? Map<*, *>)
            GestureCommand(
                commandId = commandId,
                gestureType = gestureType,
                target = target,
                endTarget = endTarget,
                options = options,
            )
        } catch (e: Exception) {
            AgentLogger.Auto.e("Failed to parse gesture command", e)
            null
        }
    }

    private fun parseGestureTarget(targetObj: Any?): GestureTarget? {
        if (targetObj == null) return null
        @Suppress("UNCHECKED_CAST")
        val targetMap = targetObj as? Map<String, Any?> ?: return null
        return when ((targetMap["type"] as? String)?.lowercase()) {
            "coordinates" -> parseCoordinatesTarget(targetMap)
            "ui_element" -> parseUIElementTarget(targetMap)
            "direction" -> parseDirectionTarget(targetMap)
            else -> when {
                targetMap.containsKey("x") && targetMap.containsKey("y") -> parseCoordinatesTarget(targetMap)
                targetMap.containsKey("direction") -> parseDirectionTarget(targetMap)
                targetMap.containsKey("text") || targetMap.containsKey("resource_id") -> parseUIElementTarget(targetMap)
                else -> null
            }
        }
    }

    private fun parseCoordinatesTarget(map: Map<String, Any?>): GestureTarget.Coordinates? {
        val x = (map["x"] as? Number)?.toFloat() ?: return null
        val y = (map["y"] as? Number)?.toFloat() ?: return null
        val normalized = when {
            map.containsKey("normalized") -> map["normalized"] as? Boolean ?: false
            map.containsKey("format") -> (map["format"] as? String) != "pixels"
            else -> {
                AgentLogger.Auto.e("Invalid gesture: missing format/normalized field")
                return null
            }
        }
        return GestureTarget.Coordinates(x, y, normalized)
    }

    private fun parseUIElementTarget(map: Map<String, Any?>): GestureTarget.UIElement {
        return GestureTarget.UIElement(
            text = map["text"] as? String,
            resourceId = (map["resource_id"] ?: map["resourceId"]) as? String,
            contentDesc = (map["content_desc"] ?: map["contentDesc"]) as? String,
            index = (map["index"] as? Number)?.toInt() ?: 0,
        )
    }

    private fun parseDirectionTarget(map: Map<String, Any?>): GestureTarget.Direction? {
        val dirStr = (map["direction"] as? String)?.uppercase() ?: return null
        val direction = try {
            SwipeDirection.valueOf(dirStr)
        } catch (e: Exception) {
            return null
        }
        val distanceRatio = (map["distance_ratio"] as? Number)?.toFloat() ?: 0.5f
        return GestureTarget.Direction(direction, distanceRatio)
    }

    private fun parseGestureOptions(optionsMap: Map<*, *>?): GestureOptions {
        if (optionsMap == null) return GestureOptions()
        return GestureOptions(
            durationMs = (optionsMap["duration_ms"] as? Number)?.toLong() ?: 100L,
            holdMs = (optionsMap["hold_ms"] as? Number)?.toLong() ?: 0L,
            retryCount = (optionsMap["retry_count"] as? Number)?.toInt() ?: 2,
            retryDelayMs = (optionsMap["retry_delay_ms"] as? Number)?.toLong() ?: 500L,
            timeoutMs = (optionsMap["timeout_ms"] as? Number)?.toLong() ?: 5000L,
        )
    }

    private fun executeLegacyGesture(
        action: String,
        payload: Map<String, Any?>,
        service: AuraAccessibilityService,
    ): CommandResult {
        return when (action.lowercase()) {
            "tap", "click" -> {
                val x = (payload["x"] as? Number)?.toInt()
                    ?: return CommandResult(success = false, error = "Missing x coordinate")
                val y = (payload["y"] as? Number)?.toInt()
                    ?: return CommandResult(success = false, error = "Missing y coordinate")
                service.performClick(x, y)
                CommandResult(success = true)
            }
            "swipe" -> {
                val x1 = (payload["x1"] as? Number)?.toInt() ?: (payload["startX"] as? Number)?.toInt()
                    ?: return CommandResult(success = false, error = "Missing start x coordinate")
                val y1 = (payload["y1"] as? Number)?.toInt() ?: (payload["startY"] as? Number)?.toInt()
                    ?: return CommandResult(success = false, error = "Missing start y coordinate")
                val x2 = (payload["x2"] as? Number)?.toInt() ?: (payload["endX"] as? Number)?.toInt()
                    ?: return CommandResult(success = false, error = "Missing end x coordinate")
                val y2 = (payload["y2"] as? Number)?.toInt() ?: (payload["endY"] as? Number)?.toInt()
                    ?: return CommandResult(success = false, error = "Missing end y coordinate")
                val duration = (payload["duration"] as? Number)?.toLong() ?: 300L
                service.performSwipe(x1, y1, x2, y2, duration)
                CommandResult(success = true)
            }
            "scroll_up" -> { service.performScroll("up"); CommandResult(success = true) }
            "scroll_down" -> { service.performScroll("down"); CommandResult(success = true) }
            "back" -> { service.performBack(); CommandResult(success = true) }
            "home" -> { service.performHome(); CommandResult(success = true) }
            "dismiss_keyboard" -> { service.dismissKeyboard(); CommandResult(success = true) }
            "press_enter" -> {
                val success = service.performEnterAction()
                CommandResult(success = success)
            }
            "press_search" -> {
                service.pressKeyEvent(android.view.KeyEvent.KEYCODE_SEARCH)
                CommandResult(success = true)
            }
            "type", "text_input", "input" -> {
                val text = payload["text"] as? String
                    ?: return CommandResult(success = false, error = "Missing text for input")
                val success = service.performTextInput(text)
                if (success) {
                    CommandResult(success = true)
                } else {
                    CommandResult(success = false, error = "Text input failed - no focused input field")
                }
            }
            "capture_screenshot" -> {
                if (!service.isMediaProjectionAvailable()) {
                    requestScreenCapturePermissionPrompt()
                    CommandResult(success = false, error = "Screen capture permission required - prompt shown")
                } else {
                    service.captureScreen()
                    CommandResult(success = true)
                }
            }
            "control_torch", "control_flashlight", "toggle_flashlight",
            "wifi_on", "wifi_off", "toggle_wifi",
            "bluetooth_on", "bluetooth_off", "toggle_bluetooth",
            "volume_up", "volume_down", "mute", "unmute",
            "brightness_up", "brightness_down",
            -> {
                service.executeSystemAction(action)
                CommandResult(success = true)
            }
            else -> CommandResult(success = false, error = "Unknown gesture action: $action")
        }
    }

    private fun executeCaptureScreenshot(): CommandResult {
        val service = AuraAccessibilityService.instance
            ?: return CommandResult(success = false, error = "AccessibilityService not available")
        return try {
            if (!service.isMediaProjectionAvailable()) {
                requestScreenCapturePermissionPrompt()
                CommandResult(success = false, error = "Screen capture permission required - prompt shown")
            } else {
                service.captureScreen()
                CommandResult(success = true)
            }
        } catch (e: Exception) {
            AgentLogger.Auto.e("Screenshot capture failed", e)
            CommandResult(success = false, error = "Screenshot capture failed: ${e.message}")
        }
    }

    private fun requestScreenCapturePermissionPrompt() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("REQUEST_SCREEN_CAPTURE", true)
                putExtra("REQUEST_SOURCE", "command")
                putExtra("FINISH_AFTER_PERMISSION", true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AgentLogger.Auto.e("Failed to start permission request activity", e)
        }
    }

    private fun executeSendMessage(): CommandResult =
        CommandResult(success = false, error = "Message sending not yet implemented")
}
