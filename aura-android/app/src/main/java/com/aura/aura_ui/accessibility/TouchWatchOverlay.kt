package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.aura.aura_ui.utils.AgentLogger

/**
 * A real "somebody touched the screen" signal — the thing the pause design was built
 * without.
 *
 * ### Why (device evidence, 2026-08-05)
 *
 * [com.aura.aura_ui.services.SelfActionWindow]'s KDoc opens with *"Android exposes no clean
 * public API for 'a finger touched the screen anywhere', so pause cannot fire on contact. It
 * fires on the first effect."* Everything downstream followed from that: watch accessibility
 * events, then try to work out who caused them.
 *
 * It does not work, and the device said so plainly. One session recorded 13 false pauses and
 * every one was `TYPE_VIEW_SCROLLED` emitted by a list settling after AURA's own tap — the
 * identical event a person flicking the screen produces. Two attempts to separate them by
 * timing failed, because they are not separable: the same event, from the same app, at
 * timings that overlap.
 *
 * ### The mechanism
 *
 * A window with `FLAG_WATCH_OUTSIDE_TOUCH` receives `MotionEvent.ACTION_OUTSIDE` when a
 * touch lands outside its bounds, and — the important part — **does not consume it**. The
 * touch continues to whatever is underneath. A 1×1 transparent window therefore reports
 * essentially every touch on the device while interfering with none of them.
 *
 * Only the initial press is reported, which is exactly right: the question is "is a person
 * touching this phone", not "what are they drawing".
 *
 * ### Attribution is easy here
 *
 * `dispatchGesture` injects at the input layer, so AURA's own taps arrive as touches too and
 * [com.aura.aura_ui.services.SelfActionWindow] still applies. But an injected touch lands
 * *at* dispatch time — tens of milliseconds — rather than a second and a half later during a
 * repaint. The settle window gets to do the job it was designed for instead of being
 * stretched to cover screen animation.
 *
 * ### Shape
 *
 * Modelled on [AuraAccessibilityService.acquireKeepScreenOn]: a 1×1 alpha-0
 * `TYPE_ACCESSIBILITY_OVERLAY`, owned by the service so it dies on unbind, and never
 * throwing — a device that refuses the window degrades to the accessibility-event path
 * rather than losing the service.
 *
 * Note the flags differ from the keep-awake window in one way that matters:
 * `FLAG_NOT_TOUCHABLE` is **absent**. A not-touchable window is invisible to the input
 * system and would never be told about outside touches at all.
 */
internal class TouchWatchOverlay(
    private val service: AccessibilityService,
    private val onTouch: () -> Unit,
) {
    private var view: View? = null

    /** Adds the watcher window (idempotent). Main thread only. @return false if refused. */
    fun attach(): Boolean {
        if (view != null) return true
        return runCatching {
            val watcher = object : View(service) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.action == MotionEvent.ACTION_OUTSIDE) onTouch()
                    // Never consume: this window observes, it does not intercept.
                    return false
                }
            }

            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // WATCH_OUTSIDE_TOUCH is the whole feature. NOT_FOCUSABLE keeps the
                // keyboard and IME away from a 1×1 invisible window. NOT_TOUCHABLE is
                // deliberately NOT set — it would remove this window from the input system
                // entirely and no outside touch would ever be reported.
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 0f
            }

            (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager)
                .addView(watcher, params)
            view = watcher
            AgentLogger.UI.i("Touch-watch overlay attached — real touch signal active")
            true
        }.getOrElse {
            // Degrade, do not die: without this the accessibility-event path still pauses
            // on a real CLICKED, it just cannot see scrolls or drags.
            AgentLogger.UI.w("Touch-watch overlay failed (${it.message}) — falling back to event signals")
            false
        }
    }

    /** Removes the watcher. Safe without a prior [attach]. */
    fun detach() {
        val current = view ?: return
        view = null
        runCatching {
            (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager)
                .removeView(current)
        }
    }
}
