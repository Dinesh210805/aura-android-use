package com.aura.aura_ui.agent

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the display on for the duration of one automation run.
 *
 * A [PowerManager.PARTIAL_WAKE_LOCK] (used elsewhere for voice capture) only keeps the CPU
 * alive — the screen still times out, and once the display is non-interactive both
 * `dispatchGesture` and screenshot capture fail, killing the run. Automation needs a *screen*
 * wake lock. `SCREEN_DIM_WAKE_LOCK` is deprecated in favour of `FLAG_KEEP_SCREEN_ON`, but that
 * flag needs an app-owned visible window and the agent runs from a windowless service context,
 * so the wake lock is the sanctioned fallback (same pattern as `WakeWordListeningService`).
 * DIM (not BRIGHT) so a long run can drop the backlight — capture reads the framebuffer, and
 * gestures only need an interactive display, so dimming costs nothing.
 *
 * One instance per run, acquire/release bracketed around the run body. Each overlapping run
 * (e.g. `drive_phone` + a manual run) holds its own lock; the screen sleeps only when the last
 * one releases. [MAX_HOLD_MS] is a leak backstop: if a release is ever missed, the OS drops the
 * lock instead of pinning the screen (and draining the battery) forever.
 */
class RunKeepAwake(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Keep the screen on until [release] (or the [MAX_HOLD_MS] backstop). ACQUIRE_CAUSES_WAKEUP
     * also turns the display back on if the run starts with it already off (e.g. a wake-word
     * command issued to a dark phone). Never throws — a keep-awake failure must not kill a run.
     */
    fun acquire() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                LOCK_TAG,
            ).apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS)
            }
            Log.i(TAG, "Screen keep-awake acquired for automation run")
        }.onFailure { Log.w(TAG, "Could not acquire keep-awake — screen may sleep mid-run", it) }
    }

    /** Let the screen sleep normally again. Safe to call without a prior [acquire]. */
    fun release() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure { Log.w(TAG, "Error releasing keep-awake", it) }
        wakeLock = null
    }

    companion object {
        private const val TAG = "RunKeepAwake"
        private const val LOCK_TAG = "aura:automation_keep_awake"

        // Backstop only — normal runs release in the agent's finally. 30 min comfortably exceeds
        // the longest plausible run (maxIterations=50) without risking an all-night screen pin.
        private const val MAX_HOLD_MS = 30L * 60L * 1000L
    }
}
