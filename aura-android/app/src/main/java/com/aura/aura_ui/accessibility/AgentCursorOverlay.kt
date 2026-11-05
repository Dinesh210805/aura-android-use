package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/**
 * The arrow that shows where AURA is touching: it glides to each tap and rides each swipe or
 * drawn stroke, leaving a trail that fades once the finger lifts.
 *
 * - Contract: every public call is safe from any thread and never throws. Feedback must never
 *   break the gesture it announces.
 * - Why one full-screen `FLAG_NOT_TOUCHABLE` window: the trail can go anywhere on screen, and a
 *   not-touchable window is skipped by input, so AURA's injected touches and the user's pass
 *   straight through. The window is removed [IDLE_MS] after the last move so it does not sit
 *   over apps between runs.
 * - Why it hides for capture: perception must never see the arrow, or the model would ground
 *   on it. `AuraAccessibilityService.withAuraOverlaysHidden` calls [hideForCapture] and
 *   [restoreAfterCapture] around every screenshot.
 * - Reads/Writes: nothing; the window's root carries AURA's package, which
 *   [WindowRootSelection] already drops from the UI tree.
 */
internal class AgentCursorOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: CursorView? = null
    private var motion: ValueAnimator? = null
    private var trailFade: ValueAnimator? = null
    private val detachRunnable = Runnable { detach() }

    /** True while the arrow window exists. Read off the main thread to skip a capture wait. */
    @Volatile
    var isShowing = false
        private set

    /** Glide the arrow so its dot lands on ([x], [y]). A fresh arrow glides in from up-left. */
    fun pointAt(x: Float, y: Float): Unit = onMain {
        val fresh = view == null
        val v = attach() ?: return@onMain
        val d = service.resources.displayMetrics.density
        val from = if (fresh) (x - ENTRY_DX_DP * d) to (y - ENTRY_DY_DP * d) else v.cx to v.cy
        run(v, listOf(from, x to y), GLIDE_MS, 0L, trail = false)
    }

    /** Ride [points] (screen pixels) over [durationMs], after [delayMs] parked on the first point. */
    fun trace(points: List<Pair<Float, Float>>, durationMs: Long, delayMs: Long = 0L): Unit = onMain {
        if (points.isEmpty()) return@onMain
        val v = attach() ?: return@onMain
        v.cx = points[0].first
        v.cy = points[0].second
        run(v, points, durationMs.coerceAtLeast(1L), delayMs.coerceAtLeast(0L), trail = true)
    }

    /** Main thread only. Hides the arrow; returns false when there was nothing to hide. */
    fun hideForCapture(): Boolean {
        val v = view ?: return false
        v.visibility = View.INVISIBLE
        return true
    }

    /** Main thread only. Undoes [hideForCapture]; safe if the window has gone meanwhile. */
    fun restoreAfterCapture() {
        view?.visibility = View.VISIBLE
    }

    /** Removes the window. Safe without a prior move; call on service unbind. */
    fun detach(): Unit = onMain {
        handler.removeCallbacks(detachRunnable)
        motion?.cancel()
        trailFade?.cancel()
        val v = view ?: return@onMain
        view = null
        isShowing = false
        runCatching { service.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    private fun run(
        v: CursorView,
        points: List<Pair<Float, Float>>,
        durationMs: Long,
        delayMs: Long,
        trail: Boolean,
    ) {
        motion?.cancel()
        trailFade?.cancel()
        handler.removeCallbacks(detachRunnable)
        v.trail = if (trail) Path().apply { moveTo(points[0].first, points[0].second) } else null
        v.trailAlpha = 1f
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = if (trail) LinearInterpolator() else DecelerateInterpolator()
            addUpdateListener {
                val (x, y) = pointAlong(points, it.animatedFraction)
                v.cx = x
                v.cy = y
                v.trail?.lineTo(x, y)
                v.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (v.trail != null) fadeTrail(v)
                    handler.postDelayed(detachRunnable, IDLE_MS)
                }
            })
            start()
        }
    }

    private fun fadeTrail(v: CursorView) {
        trailFade = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = TRAIL_FADE_MS
            addUpdateListener {
                v.trailAlpha = it.animatedValue as Float
                v.invalidate()
            }
            start()
        }
    }

    private fun attach(): CursorView? {
        view?.let { return it }
        return runCatching {
            val v = CursorView()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            requireNotNull(service.getSystemService(WindowManager::class.java)).addView(v, params)
            view = v
            isShowing = true
            v
        }.onFailure { Log.w(TAG, "cursor overlay refused (non-fatal): ${it.message}") }.getOrNull()
    }

    private fun onMain(block: () -> Unit) {
        val safe = { runCatching(block).onFailure { Log.w(TAG, "cursor failed (non-fatal): ${it.message}") } }
        if (Looper.myLooper() == Looper.getMainLooper()) safe() else handler.post { safe() }
    }

    /** A 28 dp dart: white fill, ink outline, soft shadow; its dot sits on ([cx], [cy]). */
    private inner class CursorView : View(service) {
        var cx = 0f
        var cy = 0f
        var trail: Path? = null
        var trailAlpha = 1f

        private val density = resources.displayMetrics.density
        private val dart = Path().apply {
            // Design units are dp, with the touch dot at (DOT_X, DOT_Y).
            moveTo(3f - DOT_X, 2.5f - DOT_Y)
            lineTo(24.5f - DOT_X, 12.6f - DOT_Y)
            lineTo(14.4f - DOT_X, 15.2f - DOT_Y)
            lineTo(10.3f - DOT_X, 25.2f - DOT_Y)
            close()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            setShadowLayer(4f, 0f, 1f, 0x55000000)
        }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.5f
            color = INK
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 4f * density
            color = Color.WHITE
        }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        override fun onDraw(canvas: Canvas) {
            trail?.let {
                trailPaint.alpha = (TRAIL_MAX_ALPHA * trailAlpha).toInt()
                canvas.drawPath(it, trailPaint)
            }
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(density, density)
            canvas.drawPath(dart, fill)
            canvas.drawPath(dart, ink)
            canvas.drawCircle(0f, 0f, 1.6f, dot)
            canvas.restore()
        }
    }

    private companion object {
        const val TAG = "AgentCursor"
        const val GLIDE_MS = 180L
        const val IDLE_MS = 3_000L
        const val TRAIL_FADE_MS = 450L
        const val TRAIL_MAX_ALPHA = 200
        const val ENTRY_DX_DP = 60f
        const val ENTRY_DY_DP = 90f
        const val DOT_X = 14.1f
        const val DOT_Y = 14.6f
        const val INK = 0xFF0A0A0A.toInt()
    }
}

/**
 * The point [fraction] (0..1) of the way along the polyline [points], by length.
 *
 * Contract: one point returns that point; a fraction outside 0..1 is clamped; zero-length
 * segments are skipped.
 */
internal fun pointAlong(points: List<Pair<Float, Float>>, fraction: Float): Pair<Float, Float> {
    if (points.size == 1) return points[0]
    val lengths = points.zipWithNext { (x1, y1), (x2, y2) -> hypot(x2 - x1, y2 - y1) }
    var remaining = lengths.sum() * fraction.coerceIn(0f, 1f)
    for (i in lengths.indices) {
        val len = lengths[i]
        if (remaining <= len && len > 0f) {
            val t = remaining / len
            val (x1, y1) = points[i]
            val (x2, y2) = points[i + 1]
            return (x1 + (x2 - x1) * t) to (y1 + (y2 - y1) * t)
        }
        remaining -= len
    }
    return points.last()
}
