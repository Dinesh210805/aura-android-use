package com.aura.aura_ui.accessibility.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.aura.aura_ui.services.SelfActionWindow
import com.aura.aura_ui.utils.AgentLogger
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GestureInjector(private val service: AccessibilityService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val resolver = CoordinateResolver(service)
    private val builder = GestureBuilder()
    private val dispatcher = GestureDispatcher(service)
    private val errorHandler = GestureErrorHandler(service)
    private val retryExecutor = RetryExecutor(resolver, builder, dispatcher, errorHandler)

    fun execute(command: GestureCommand, callback: GestureCallback) {
        scope.launch {
            val result = runInternal(command)
            dispatchToCallback(command, result, callback)
        }
    }

    suspend fun executeAndAwait(command: GestureCommand): GestureResult = runInternal(command)

    /**
     * Traces one finger path exactly as given (no humanized curve), after an optional press.
     *
     * - Contract: points are pixels, clamped onto the screen; one point is a dot. Returns true
     *   only when the whole stroke completed.
     * - Why two dispatches for a hold: a press then a move is a continued stroke, and a
     *   continued stroke must be its own dispatch. Packing both into one GestureDescription
     *   strands the finger down (see [GestureBuilder.buildSwipe]).
     * - Fails: if the move is not delivered after the press, a lift is sent at the press point
     *   so the pointer is never left down.
     */
    suspend fun executeStroke(points: List<Pair<Int, Int>>, durationMs: Long, holdMs: Long): Boolean {
        val maxX = (resolver.screenWidth - 1).toFloat()
        val maxY = (resolver.screenHeight - 1).toFloat()
        val pts = points.map { (x, y) -> x.toFloat().coerceIn(0f, maxX) to y.toFloat().coerceIn(0f, maxY) }
        val (x0, y0) = pts.first()
        val path = Path().apply {
            moveTo(x0, y0)
            pts.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        if (holdMs <= 0) return send(GestureDescription.StrokeDescription(path, 0L, durationMs), durationMs)

        val press = GestureDescription.StrokeDescription(Path().apply { moveTo(x0, y0) }, 0L, holdMs, true)
        if (!send(press, holdMs)) return false
        if (send(press.continueStroke(path, 0L, durationMs, false), durationMs)) return true
        send(press.continueStroke(Path().apply { moveTo(x0, y0) }, 0L, 1L, false), 1L)
        return false
    }

    private suspend fun send(stroke: GestureDescription.StrokeDescription, durationMs: Long): Boolean =
        dispatcher.dispatch(
            commandId = "stroke_${UUID.randomUUID()}",
            gesture = GestureDescription.Builder().addStroke(stroke).build(),
            timeoutMs = durationMs + STROKE_TIMEOUT_SLACK_MS,
            kind = SelfActionWindow.ActionKind.SCROLL,
        ) == DispatchResult.COMPLETED

    fun cancelAll() {
        dispatcher.cancelAll()
        AgentLogger.Auto.i("GestureInjector: All gestures cancelled")
    }

    fun destroy() {
        cancelAll()
        scope.cancel()
    }

    fun getScreenDimensions(): Pair<Int, Int> = Pair(resolver.screenWidth, resolver.screenHeight)

    private suspend fun runInternal(command: GestureCommand): GestureResult {
        val validationError = validateCommand(command)
        if (validationError != null) {
            return GestureResult.Failure(command.commandId, validationError.first, validationError.second)
        }
        return try {
            val result = retryExecutor.executeWithRetry(command)
            logResult(result)
            result
        } catch (e: Exception) {
            AgentLogger.Auto.e("Gesture execution exception", e)
            GestureResult.Failure(command.commandId, GestureError.DISPATCH_FAILED, e.message ?: "Unknown error")
        }
    }

    private fun logResult(result: GestureResult) {
        when (result) {
            is GestureResult.Success -> AgentLogger.Auto.i(
                "Gesture executed successfully",
                mapOf("commandId" to result.commandId, "executionTimeMs" to result.executionTimeMs),
            )
            is GestureResult.Failure -> AgentLogger.Auto.w(
                "Gesture execution failed",
                mapOf("commandId" to result.commandId, "error" to result.error.name, "details" to (result.details ?: "")),
            )
            is GestureResult.Cancelled -> AgentLogger.Auto.i(
                "Gesture cancelled",
                mapOf("commandId" to result.commandId),
            )
        }
    }

    private fun dispatchToCallback(command: GestureCommand, result: GestureResult, callback: GestureCallback) {
        when (result) {
            is GestureResult.Success -> callback.onSuccess(command, result.executionTimeMs)
            is GestureResult.Failure -> callback.onFailure(command, result.error, result.details)
            is GestureResult.Cancelled -> callback.onCancelled(command)
        }
    }

    private fun validateCommand(command: GestureCommand): Pair<GestureError, String>? {
        if (service.rootInActiveWindow == null) {
            return Pair(GestureError.SERVICE_UNAVAILABLE, "No active window available")
        }
        if (command.commandId.isBlank()) {
            return Pair(GestureError.INVALID_COMMAND, "Command ID is blank")
        }
        if (command.gestureType == GestureType.SWIPE &&
            command.target is GestureTarget.Coordinates &&
            command.endTarget == null
        ) {
            return Pair(GestureError.INVALID_COMMAND, "Swipe requires end_target for coordinate targets")
        }
        return null
    }
}

/** Head-room past a stroke's own length before [GestureDispatcher] calls it timed out. */
private const val STROKE_TIMEOUT_SLACK_MS = 2_000L
