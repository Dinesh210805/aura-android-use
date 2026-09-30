package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * Visual tap feedback: a brief expanding white ring (with a soft core dot)
 * drawn at the exact pixel the agent is about to touch, so the user can SEE
 * where automation is tapping in real time — som_id taps included, because
 * this hooks the single gesture-dispatch funnel AFTER the server has resolved
 * som_id → coordinates.
 *
 * Implementation notes:
 *  - TYPE_ACCESSIBILITY_OVERLAY: available to the accessibility service with
 *    no extra permission, always above app content.
 *  - NOT_TOUCHABLE + NOT_FOCUSABLE: the flash can never intercept the very
 *    gesture it is announcing.
 *  - Short-lived (~[FLASH_MS]): removed well before the post-action settle
 *    window ends, so it cannot appear in perceive_screen captures. Our own
 *    package is also already filtered from ScreenSettle's event stream.
 *  - Best-effort: any failure is swallowed — feedback must never break the
 *    gesture itself.
 */
internal class TapFlashOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())

    /** Flash at ([x], [y]); when [x2]/[y2] are given (swipe), a second, smaller ring marks the end point. */
    fun flash(x: Float, y: Float, x2: Float? = null, y2: Float? = null) {
        handler.post {
            runCatching { show(x, y, x2, y2) }
                .onFailure { Log.w(TAG, "tap flash failed (non-fatal): ${it.message}") }
        }
    }

    private fun show(x: Float, y: Float, x2: Float?, y2: Float?) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val view = FlashView(x, y, x2, y2)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(view, params)
        handler.postDelayed({ runCatching { wm.removeView(view) } }, FLASH_MS)
    }

    /** Expanding ring + fading core dot, pure Canvas (no Compose, no theme). */
    private inner class FlashView(
        private val cx: Float,
        private val cy: Float,
        private val ex: Float?,
        private val ey: Float?,
    ) : View(service) {

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLASH_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { invalidate() }
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        init {
            background = null
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val t = animator.animatedValue as Float // 0 → 1
            val density = resources.displayMetrics.density
            val fade = ((1f - t) * 255).toInt().coerceIn(0, 255)

            // Expanding ring: 8dp → 26dp radius, stroke thins as it grows.
            ringPaint.alpha = fade
            ringPaint.strokeWidth = (3.5f - 1.5f * t) * density
            canvas.drawCircle(cx, cy, (8f + 18f * t) * density, ringPaint)

            // Core dot: shrinks and fades — the precise touch point.
            corePaint.alpha = (fade * 0.9f).toInt()
            canvas.drawCircle(cx, cy, (5f * (1f - t) + 1.5f) * density, corePaint)

            // Swipe end point: smaller static ring so the direction reads at a glance.
            if (ex != null && ey != null) {
                ringPaint.alpha = (fade * 0.7f).toInt()
                canvas.drawCircle(ex, ey, (6f + 8f * t) * density, ringPaint)
            }
        }
    }

    companion object {
        private const val TAG = "TapFlashOverlay"

        /** Lifetime of the flash — well under the post-gesture settle window. */
        const val FLASH_MS = 380L
    }
}
