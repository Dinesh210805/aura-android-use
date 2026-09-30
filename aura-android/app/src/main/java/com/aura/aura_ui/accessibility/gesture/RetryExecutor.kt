package com.aura.aura_ui.accessibility.gesture

import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.delay

class RetryExecutor(
    private val resolver: CoordinateResolver,
    private val builder: GestureBuilder,
    private val dispatcher: GestureDispatcher,
    private val errorHandler: GestureErrorHandler? = null,
) {
    suspend fun executeWithRetry(command: GestureCommand): GestureResult {
        val maxAttempts = command.options.retryCount + 1
        var lastFailure: GestureResult.Failure? = null

        for (attempt in 1..maxAttempts) {
            AgentLogger.Auto.d(
                "Gesture attempt",
                mapOf(
                    "commandId" to command.commandId,
                    "attempt" to attempt,
                    "maxAttempts" to maxAttempts,
                ),
            )

            val result = executeOnce(command)

            when (result) {
                is GestureResult.Success -> return result
                is GestureResult.Cancelled -> return result
                is GestureResult.Failure -> {
                    lastFailure = result
                    if (!isRetryable(result.error)) {
                        AgentLogger.Auto.w(
                            "Non-retryable error",
                            mapOf("error" to result.error.name, "details" to (result.details ?: "")),
                        )
                        return result
                    }
                    if (attempt < maxAttempts) {
                        val recovery = errorHandler?.attemptRecovery(result.error) ?: RecoveryAction.RETRY_AFTER_DELAY
                        AgentLogger.Auto.i(
                            "Retrying gesture",
                            mapOf("attempt" to attempt, "recovery" to recovery.name),
                        )
                        if (recovery == RecoveryAction.RETRY_AFTER_DELAY) {
                            delay(command.options.retryDelayMs)
                        }
                        if (recovery == RecoveryAction.ABORT) {
                            return result
                        }
                    }
                }
            }
        }

        return lastFailure ?: GestureResult.Failure(
            command.commandId,
            GestureError.DISPATCH_FAILED,
            "All retry attempts exhausted",
        )
    }

    private suspend fun executeOnce(command: GestureCommand): GestureResult {
        val startTime = System.currentTimeMillis()

        val (startCoord, endCoord) =
            resolveTargets(command)
                ?: return GestureResult.Failure(command.commandId, GestureError.TARGET_NOT_FOUND)

        if (!resolver.isWithinBounds(startCoord.x, startCoord.y)) {
            return GestureResult.Failure(
                command.commandId,
                GestureError.OUT_OF_BOUNDS,
                "Start coordinates (${startCoord.x}, ${startCoord.y}) outside screen",
            )
        }

        if (endCoord != null && !resolver.isWithinBounds(endCoord.x, endCoord.y)) {
            return GestureResult.Failure(
                command.commandId,
                GestureError.OUT_OF_BOUNDS,
                "End coordinates (${endCoord.x}, ${endCoord.y}) outside screen",
            )
        }

        val gesture = builder.build(command, startCoord, endCoord)
        var dispatchResult =
            dispatcher.dispatch(command.commandId, gesture, command.options.timeoutMs, command.gestureType.selfActionKind)

        // Some OEMs reject multi-segment (continued) strokes. If the humanized gesture
        // is rejected outright, fall back once to the plain single-stroke build so we're
        // never worse than the pre-humanization behaviour.
        if (dispatchResult == DispatchResult.REJECTED) {
            AgentLogger.Auto.i(
                "Humanized gesture rejected, retrying with plain stroke",
                mapOf("commandId" to command.commandId),
            )
            val plain = builder.build(command, startCoord, endCoord, humanize = false)
            dispatchResult =
                dispatcher.dispatch(command.commandId, plain, command.options.timeoutMs, command.gestureType.selfActionKind)
        }

        val executionTime = System.currentTimeMillis() - startTime

        return when (dispatchResult) {
            DispatchResult.COMPLETED -> GestureResult.Success(command.commandId, executionTime)
            DispatchResult.CANCELLED -> GestureResult.Failure(command.commandId, GestureError.GESTURE_CANCELLED)
            DispatchResult.REJECTED -> GestureResult.Failure(
                command.commandId,
                GestureError.DISPATCH_FAILED,
                "System rejected gesture",
            )
            DispatchResult.TIMEOUT -> GestureResult.Failure(command.commandId, GestureError.TIMEOUT)
        }
    }

    private fun resolveTargets(command: GestureCommand): Pair<ResolvedCoordinate, ResolvedCoordinate?>? {
        return when (val target = command.target) {
            is GestureTarget.Direction -> {
                val (start, end) = resolver.resolveDirection(target)
                Pair(start, end)
            }
            is GestureTarget.Coordinates, is GestureTarget.UIElement -> {
                val start = resolver.resolve(target) ?: return null
                val end = command.endTarget?.let { resolver.resolve(it) }
                Pair(start, end)
            }
        }
    }

    private fun isRetryable(error: GestureError): Boolean {
        return when (error) {
            GestureError.DISPATCH_FAILED,
            GestureError.TIMEOUT,
            GestureError.GESTURE_CANCELLED,
            -> true
            GestureError.TARGET_NOT_FOUND,
            GestureError.OUT_OF_BOUNDS,
            GestureError.SERVICE_UNAVAILABLE,
            GestureError.INVALID_COMMAND,
            GestureError.SCREEN_CHANGED,
            -> false
        }
    }
}
