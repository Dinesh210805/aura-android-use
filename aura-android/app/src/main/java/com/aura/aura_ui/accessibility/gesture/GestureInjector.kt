package com.aura.aura_ui.accessibility.gesture

import android.accessibilityservice.AccessibilityService
import com.aura.aura_ui.utils.AgentLogger
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
