package com.aura.aura_ui.services.keepawake

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.agent.RunKeepAwake

/**
 * Production keep-awake mechanism, layered for device coverage (API 26–36,
 * OEM battery managers included):
 *
 *  1. **Keep-on (primary):** a 1×1 invisible `TYPE_ACCESSIBILITY_OVERLAY`
 *     window with `FLAG_KEEP_SCREEN_ON`, owned by [AuraAccessibilityService].
 *     Window flags are honored on every Android build and are NOT subject to
 *     the wake-lock culling aggressive OEMs (OnePlus/ColorOS, MIUI, …) apply
 *     to background services. Needs no permission beyond accessibility —
 *     which automation requires anyway.
 *  2. **Keep-on (fallback):** the legacy [RunKeepAwake] SCREEN_DIM wake lock,
 *     used only when the accessibility service is not bound (in which case
 *     gestures are dead too, so this is best-effort for perception-only use).
 *  3. **Wake-up (separate concern):** window flags cannot turn a dark screen
 *     ON. If the display is non-interactive at engage time, [ScreenWakeActivity]
 *     is launched — the sanctioned `setTurnScreenOn` path that works on
 *     API 27+, with legacy window flags covering API 26. (The manifest also
 *     declares TURN_SCREEN_ON so RunKeepAwake's ACQUIRE_CAUSES_WAKEUP regains
 *     effect on Android 14+, where it is otherwise silently ignored.)
 *
 * Engage/disengage post to the main thread (WindowManager requirement); the
 * shared handler preserves ordering between the two.
 */
class DefaultScreenAwakeMechanism(
    private val context: Context,
) : ScreenAwakeMechanism {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val fallbackLock = RunKeepAwake(context)

    override fun engage() {
        if (!powerManager.isInteractive) {
            Log.i(TAG, "Display is off — waking it for the automation run")
            ScreenWakeActivity.wake(context)
        }
        mainHandler.post {
            val viaOverlay = AuraAccessibilityService.instance?.acquireKeepScreenOn() == true
            if (!viaOverlay) {
                Log.w(TAG, "Accessibility overlay unavailable — falling back to screen wake lock")
                fallbackLock.acquire()
            }
        }
    }

    override fun disengage() {
        mainHandler.post {
            AuraAccessibilityService.instance?.releaseKeepScreenOn()
            fallbackLock.release()
        }
    }

    companion object {
        private const val TAG = "ScreenAwake"
    }
}
