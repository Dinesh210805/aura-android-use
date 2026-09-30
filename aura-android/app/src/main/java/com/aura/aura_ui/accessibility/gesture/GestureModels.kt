package com.aura.aura_ui.accessibility.gesture

import android.graphics.Rect

enum class GestureType { TAP, LONG_PRESS, SWIPE, SCROLL }

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

enum class GestureError {
    TARGET_NOT_FOUND,
    OUT_OF_BOUNDS,
    DISPATCH_FAILED,
    TIMEOUT,
    GESTURE_CANCELLED,
    SCREEN_CHANGED,
    SERVICE_UNAVAILABLE,
    INVALID_COMMAND,
}

sealed class GestureTarget {
    data class Coordinates(
        val x: Float,
        val y: Float,
        val normalized: Boolean = true,
    ) : GestureTarget()

    data class UIElement(
        val text: String? = null,
        val resourceId: String? = null,
        val contentDesc: String? = null,
        val index: Int = 0,
    ) : GestureTarget()

    data class Direction(
        val direction: SwipeDirection,
        val distanceRatio: Float = 0.5f,
    ) : GestureTarget()
}

data class GestureOptions(
    val durationMs: Long = 100L,
    val holdMs: Long = 0L,
    val retryCount: Int = 2,
    val retryDelayMs: Long = 500L,
    val timeoutMs: Long = 5000L,
)

data class GestureCommand(
    val commandId: String,
    val gestureType: GestureType,
    val target: GestureTarget,
    val endTarget: GestureTarget.Coordinates? = null,
    val options: GestureOptions = GestureOptions(),
)

data class ResolvedCoordinate(
    val x: Int,
    val y: Int,
    val confidence: Float = 1.0f,
    val elementText: String? = null,
    val bounds: Rect? = null,
)

sealed class GestureResult {
    data class Success(
        val commandId: String,
        val executionTimeMs: Long,
    ) : GestureResult()

    data class Failure(
        val commandId: String,
        val error: GestureError,
        val details: String? = null,
    ) : GestureResult()

    data class Cancelled(
        val commandId: String,
    ) : GestureResult()
}

interface GestureCallback {
    fun onSuccess(
        command: GestureCommand,
        executionTimeMs: Long,
    )

    fun onFailure(
        command: GestureCommand,
        error: GestureError,
        details: String? = null,
    )

    fun onCancelled(command: GestureCommand)
}
