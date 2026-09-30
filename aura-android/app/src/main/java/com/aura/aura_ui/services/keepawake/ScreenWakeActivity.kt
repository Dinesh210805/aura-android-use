package com.aura.aura_ui.services.keepawake

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Invisible, instant-finish activity whose only job is to turn the display ON
 * when an automation run starts against a dark phone.
 *
 * Why an activity: `FLAG_KEEP_SCREEN_ON` (our keep-on mechanism) cannot wake a
 * screen that is already off, and `PowerManager.ACQUIRE_CAUSES_WAKEUP` is
 * ignored on Android 14+ for apps targeting API 34+ unless TURN_SCREEN_ON is
 * granted — OEMs vary. `Activity.setTurnScreenOn` (API 27+) is the sanctioned,
 * universally honored path; API 26 uses the equivalent legacy window flags.
 *
 * The activity is translucent, excluded from recents, `noHistory`, and
 * finishes ~½ s after its window attaches (finishing in onCreate would tear
 * the window down before the turn-screen-on request is processed). Launching
 * from a service context is permitted because the app holds the user-granted
 * SYSTEM_ALERT_WINDOW permission (background-activity-launch exemption).
 *
 * Deliberately does NOT dismiss the keyguard: on a locked phone the display
 * wakes to the lock screen and automation fails with a visible reason —
 * defeating the lock is a policy line this app does not cross.
 */
class ScreenWakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        Log.i(TAG, "Waking display for automation")
        window.decorView.postDelayed({ if (!isFinishing) finish() }, FINISH_DELAY_MS)
    }

    companion object {
        private const val TAG = "ScreenAwake"

        // Long enough for the system to process the turn-screen-on request
        // after window attach; short enough to be invisible to the user.
        private const val FINISH_DELAY_MS = 500L

        /** Fire-and-forget wake from any context (service threads included). */
        fun wake(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, ScreenWakeActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                        ),
                )
            }.onFailure { Log.w(TAG, "Could not launch ScreenWakeActivity — display may stay off", it) }
        }
    }
}
