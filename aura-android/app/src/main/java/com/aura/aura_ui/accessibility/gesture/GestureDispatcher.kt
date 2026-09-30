package com.aura.aura_ui.accessibility.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import com.aura.aura_ui.services.ControlLockStore
import com.aura.aura_ui.services.SelfActionWindow
import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

enum class DispatchResult { COMPLETED, CANCELLED, REJECTED, TIMEOUT }

/**
 * How long the device keeps echoing each kind of gesture, for the control lock.
 *
 * Derived from the [GestureType] the injector already carries rather than passed in by
 * hand at every call site — the previous design had a single global window and every
 * caller silently inherited the wrong one for scrolling. Exhaustive `when` on purpose: a
 * new gesture type must state its settle time instead of defaulting into a self-pause.
 */
internal val GestureType.selfActionKind: SelfActionWindow.ActionKind
    get() = when (this) {
        // The click event lands while the finger is still down; nothing trails it.
        GestureType.TAP, GestureType.LONG_PRESS -> SelfActionWindow.ActionKind.TAP
        // The stroke is only the beginning — the list flings on after the lift, emitting
        // TYPE_VIEW_SCROLLED the whole way. This is the case that made AURA pause on its
        // own scrolling.
        GestureType.SWIPE, GestureType.SCROLL -> SelfActionWindow.ActionKind.SCROLL
    }

class GestureDispatcher(private val service: AccessibilityService) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingGestures = ConcurrentHashMap<String, Job>()

    /**
     * @param kind what this gesture *is*, so the control lock knows how long the device
     *   will keep echoing it. Defaults to [SelfActionWindow.ActionKind.TAP] — the
     *   shortest window — so a caller that forgets errs toward yielding to the human
     *   rather than toward ignoring them.
     */
    internal suspend fun dispatch(
        commandId: String,
        gesture: GestureDescription,
        timeoutMs: Long,
        kind: SelfActionWindow.ActionKind = SelfActionWindow.ActionKind.TAP,
    ): DispatchResult =
        suspendCancellableCoroutine { cont ->
            val timeoutJob =
                CoroutineScope(Dispatchers.Main).launch {
                    delay(timeoutMs)
                    if (cont.isActive) {
                        AgentLogger.Auto.w("Gesture timeout", mapOf("commandId" to commandId))
                        cont.resume(DispatchResult.TIMEOUT)
                    }
                }
            pendingGestures[commandId] = timeoutJob

            val callback =
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        timeoutJob.cancel()
                        pendingGestures.remove(commandId)
                        // Re-stamp on COMPLETION as well as before dispatch. The settle
                        // budget has to start when the finger lifts, not when the stroke
                        // begins: a scroll gesture is itself several hundred ms, and the
                        // fling that matters happens entirely after this point. Measuring
                        // from dispatch spent most of the budget on the stroke and let the
                        // fling's tail land outside the window, where it read as a human.
                        ControlLockStore.shared.noteDispatch(kind)
                        if (cont.isActive) {
                            AgentLogger.Auto.d("Gesture completed", mapOf("commandId" to commandId))
                            cont.resume(DispatchResult.COMPLETED)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        timeoutJob.cancel()
                        pendingGestures.remove(commandId)
                        if (cont.isActive) {
                            AgentLogger.Auto.w("Gesture cancelled", mapOf("commandId" to commandId))
                            cont.resume(DispatchResult.CANCELLED)
                        }
                    }
                }

            cont.invokeOnCancellation {
                timeoutJob.cancel()
                pendingGestures.remove(commandId)
            }

            // Spec 2026-07-31 — control lock. Stamp the self-action window BEFORE the
            // gesture leaves, never after: the resulting accessibility events can arrive
            // before dispatchGesture() returns, and an unstamped echo reads as the user
            // grabbing the phone — so AURA would pause on its own tap.
            ControlLockStore.shared.noteDispatch(kind)

            val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
            if (!dispatched) {
                timeoutJob.cancel()
                pendingGestures.remove(commandId)
                AgentLogger.Auto.w("Gesture rejected by system", mapOf("commandId" to commandId))
                cont.resume(DispatchResult.REJECTED)
            }
        }

    fun cancelAll() {
        pendingGestures.values.forEach { it.cancel() }
        pendingGestures.clear()
        AgentLogger.Auto.i("All pending gestures cancelled")
    }
}
