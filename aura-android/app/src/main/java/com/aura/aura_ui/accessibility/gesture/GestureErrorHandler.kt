package com.aura.aura_ui.accessibility.gesture

import android.accessibilityservice.AccessibilityService
import com.aura.aura_ui.utils.AgentLogger

enum class RecoveryAction { RETRY_IMMEDIATELY, RETRY_AFTER_DELAY, ABORT }

class GestureErrorHandler(private val service: AccessibilityService) {
    fun attemptRecovery(error: GestureError): RecoveryAction {
        return when (error) {
            GestureError.DISPATCH_FAILED -> {
                if (isSystemUIActive()) {
                    AgentLogger.Auto.i("System UI blocking gesture, pressing back")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    RecoveryAction.RETRY_AFTER_DELAY
                } else {
                    RecoveryAction.RETRY_IMMEDIATELY
                }
            }
            GestureError.GESTURE_CANCELLED -> {
                if (isSystemUIActive()) {
                    AgentLogger.Auto.i("Gesture cancelled by system UI, pressing back")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    RecoveryAction.RETRY_AFTER_DELAY
                } else {
                    RecoveryAction.RETRY_AFTER_DELAY
                }
            }
            GestureError.TIMEOUT -> RecoveryAction.RETRY_IMMEDIATELY
            GestureError.TARGET_NOT_FOUND,
            GestureError.OUT_OF_BOUNDS,
            GestureError.SCREEN_CHANGED,
            GestureError.SERVICE_UNAVAILABLE,
            GestureError.INVALID_COMMAND,
            -> RecoveryAction.ABORT
        }
    }

    private fun isSystemUIActive(): Boolean {
        return try {
            val rootNode = service.rootInActiveWindow
            val packageName = rootNode?.packageName?.toString()
            @Suppress("DEPRECATION")
            rootNode?.recycle()
            packageName == "com.android.systemui"
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error checking system UI state", e)
            false
        }
    }
}
