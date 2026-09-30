package com.aura.aura_ui.services

import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Records what the device *actually* emits, so [PauseTrigger]'s set stops being reasoning.
 *
 * ### Why this exists
 *
 * The trigger set has now been wrong twice in opposite directions, and both times the loop
 * was the same: reason about which events a finger produces → ship → observe one symptom
 * on device → adjust → ship again.
 *
 *  1. `TYPE_VIEW_FOCUSED` was included; it fires on every screen transition, and one stray
 *     event latched the lock for the process lifetime.
 *  2. It was cut back to `CLICKED` / `LONG_CLICKED` / `TOUCH_INTERACTION_START`, and the
 *     first device run never paused at all — `TOUCH_INTERACTION_START` only fires when
 *     touch exploration (TalkBack) is on, so the real set was two event types and
 *     scrolling produced nothing.
 *  3. `SCROLLED` and `TEXT_CHANGED` were re-admitted to fix that, which made AURA pause on
 *     its own scrolling and its own typing.
 *
 * Each round cost a build, an install and a manual session, and answered one bit. The
 * missing ingredient was never cleverness — it was **data**: nobody had ever looked at the
 * stream. One instrumented session answers the whole question, for this OEM, at once.
 *
 * ### Using it
 *
 * ```
 * adb shell setprop log.tag.AuraLockDiag VERBOSE     # or flip [enabled] in a debug build
 * adb logcat -s AuraLockDiag:V
 * ```
 *
 * Run the two passes the spec asks for and diff them:
 *
 *  - **(a) untouched run** — every line should read `self=true` or be a type outside the
 *    trigger set. Any `self=false human=true` here is a false pause waiting to happen, and
 *    `sinceDispatch` tells you whether the fix is a longer settle for that
 *    [SelfActionWindow.ActionKind] or a missing stamp at some seam.
 *  - **(b) human use, agent idle** — collect the types a real finger produces on this
 *    device. Anything frequent there and absent from (a) is a safe addition to the set.
 *
 * The output is deliberately one line per event with every input to the decision on it,
 * because the interesting cases are the ones where the verdict is right but the reason is
 * wrong — a self-caused event that happened to fall inside a window it should not have.
 */
internal object ControlLockDiagnostics {

    private const val TAG = "AuraLockDiag"

    /**
     * Off unless someone asks for it. This sits on `onAccessibilityEvent`, which is the
     * entire system's event firehose — logging it unconditionally would be its own
     * performance problem, and would bury real logs on a busy device.
     */
    val enabled: Boolean get() = Log.isLoggable(TAG, Log.VERBOSE)

    fun record(
        event: AccessibilityEvent,
        fromOwnApp: Boolean,
        isDriving: Boolean,
        selfCaused: Boolean,
        sinceDispatchMs: Long?,
        lastKind: SelfActionWindow.ActionKind?,
    ) {
        if (!enabled) return
        val human = PauseTrigger.isHumanSignal(event.eventType, fromOwnApp)
        Log.v(
            TAG,
            buildString {
                append(typeName(event.eventType))
                append(" pkg=").append(event.packageName ?: "?")
                append(" own=").append(fromOwnApp)
                append(" driving=").append(isDriving)
                append(" self=").append(selfCaused)
                append(" sinceDispatch=").append(sinceDispatchMs?.toString() ?: "-")
                append(" lastKind=").append(lastKind?.name ?: "-")
                append(" human=").append(human)
                // The bottom line: would this event have paused the agent?
                append(" WOULD_PAUSE=").append(human && isDriving && !selfCaused)
            },
        )
    }

    /**
     * `AccessibilityEvent.eventTypeToString` exists but is not available on every API
     * level this app supports, and a raw bitmask in the log is unreadable at 3am.
     */
    private fun typeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLLED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUSED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "SELECTED"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TOUCH_START"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TOUCH_END"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "EXPLORE_START"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TEXT_SELECTION"
        else -> "type_$type"
    }
}
