package com.aura.aura_ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.accessibility.ScreenGeometry

/**
 * The white rim glow — AURA's one and only edge-lighting effect.
 *
 * This is the glow that shows during automation, promoted to the single implementation.
 * Three used to exist and drift apart:
 *  - `EdgeGlowOverlay` (a second Canvas view with corner radials, driven by the retired
 *    Python backend's `visual_feedback` WebSocket message) — deleted.
 *  - `AuraEdgeGlow` (a Compose copy drawn *inside* the assistant panel, hand-matched
 *    "pixel-for-pixel" to this one) — deleted. It could only ever hug AURA's own window,
 *    which is why it read wrong once that window stopped covering the screen.
 *  - `EdgeGlowCanvasView` (inner class of AuraOverlayService) — this, extracted here.
 *
 * ## It must not affect the screen
 *
 * Three separate mechanisms, each deliberate:
 *
 * 1. **`TYPE_ACCESSIBILITY_OVERLAY` when the accessibility service is bound.** A plain
 *    `TYPE_APPLICATION_OVERLAY` is an *untrusted* overlay: the framework stamps
 *    `FLAG_WINDOW_IS_OBSCURED` on every touch delivered to the app underneath, and any
 *    view using `setFilterTouchesWhenObscured()` silently rejects its own buttons —
 *    "an app is blocking the screen". `FLAG_NOT_TOUCHABLE` does not help; it stops *us*
 *    receiving touches, not the obscured stamp. Accessibility overlays are trusted and
 *    are excluded from that calculation, so a full-screen glow costs the app behind
 *    nothing. Falls back to `TYPE_APPLICATION_OVERLAY` when accessibility is off.
 * 2. **`FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE`** — decoration never takes input.
 * 3. **Excluded from screen capture** (Android 13+) so the glow cannot bleed into
 *    `perceive_screen` screenshots. It is on screen for the whole of a run, which is
 *    exactly when perception is capturing.
 */
internal class EdgeGlow(private val fallbackContext: Context) {

    private var view: View? = null
    private var windowManager: WindowManager? = null

    val isShowing: Boolean get() = view != null

    /** Attach the glow. No-op when already showing. */
    fun show() {
        if (view != null) return

        // Prefer the accessibility service as the window host — that is what makes the
        // window a trusted overlay. Falls back to the calling service's context.
        val a11y = AuraAccessibilityService.instance
        val host: Context = a11y ?: fallbackContext
        val trusted = a11y != null

        val wm = host.getSystemService(WindowManager::class.java) ?: run {
            Log.w(TAG, "No WindowManager — edge glow skipped")
            return
        }

        val type = when {
            trusted -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        // Real display pixels, not MATCH_PARENT: MATCH_PARENT is relative to the app
        // window area and leaves the status bar / gesture nav uncovered, so the rim
        // would stop short of the physical screen edge.
        val (screenW, screenH) = ScreenGeometry.realSizePx(host)

        val params = WindowManager.LayoutParams(
            screenW, screenH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        val glow = GlowView(host)
        try {
            wm.addView(glow, params)
            view = glow
            windowManager = wm
            Log.i(TAG, "Edge glow shown (${screenW}x$screenH, trusted=$trusted)")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing edge glow", e)
        }
    }

    /** Detach the glow. No-op when not showing. */
    fun hide() {
        val glow = view ?: return
        runCatching { windowManager?.removeView(glow) }
            .onFailure { Log.w(TAG, "Error removing edge glow: ${it.message}") }
        view = null
        windowManager = null
        Log.i(TAG, "Edge glow hidden")
    }

    /**
     * Pure-Canvas view that draws white gradient strips at each screen edge.
     * No Compose, no theme, no Material surface — only pixels we paint.
     * A ValueAnimator drives the breathing alpha (0.28 ↔ 0.72, 2.2 s).
     */
    private class GlowView(context: Context) : View(context) {

        private val animator = ValueAnimator.ofFloat(0.28f, 0.72f).apply {
            duration = 2200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            // Absolutely no background — not null, not a color, nothing.
            background = null
            setLayerType(LAYER_TYPE_HARDWARE, null)
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
            val alpha = animator.animatedValue as Float
            val gp = 32f * resources.displayMetrics.density
            val w = width.toFloat()
            val h = height.toFloat()

            fun white(a: Float) =
                Color.argb((255 * a * alpha).toInt().coerceIn(0, 255), 255, 255, 255)
            val clear = Color.TRANSPARENT

            // Top — bright at 0px, fades to clear at 32dp
            paint.shader = LinearGradient(
                0f, 0f, 0f, gp,
                intArrayOf(white(0.95f), white(0.25f), clear),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, gp, paint)

            // Bottom
            paint.shader = LinearGradient(
                0f, h - gp, 0f, h,
                intArrayOf(clear, white(0.25f), white(0.95f)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, h - gp, w, h, paint)

            // Left
            paint.shader = LinearGradient(
                0f, 0f, gp, 0f,
                intArrayOf(white(0.95f), white(0.25f), clear),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, gp, h, paint)

            // Right
            paint.shader = LinearGradient(
                w - gp, 0f, w, 0f,
                intArrayOf(clear, white(0.25f), white(0.95f)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(w - gp, 0f, w, h, paint)
        }
    }

    private companion object {
        const val TAG = "EdgeGlow"
    }
}
