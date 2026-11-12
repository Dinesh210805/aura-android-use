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
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.aura.aura_ui.services.ControlLockStore
import com.aura.aura_ui.services.RemoteRun
import com.aura.aura_ui.services.StepPill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * AURA's hands on screen: a small white arrow (cua-driver's default arrow shape) that glides to each
 * touch, with a one-line pill under it saying what the step is for ([StepPill.line]).
 *
 * - Contract: every public call is safe from any thread and never throws; feedback must never break
 *   the gesture it announces. [start] begins following the run: the cursor stays on screen for the
 *   whole run (local or remote MCP), hides while the user has paused it, and leaves when the run
 *   ends. Outside a run a move shows it briefly, then it leaves after [IDLE_MS].
 * - Why one full-screen `FLAG_NOT_TOUCHABLE` window: the cursor, its trail and its pill can be
 *   anywhere, and a not-touchable window is skipped by input, so AURA's injected touches and the
 *   user's pass straight through.
 * - Why it hides for capture: perception must never see the arrow or its words, or the model would
 *   ground on them. `AuraAccessibilityService.withAuraOverlaysHidden` calls [hideForCapture] and
 *   [restoreAfterCapture] around every screenshot; the pill is drawn by the same view, so it goes too.
 * - Reads/Writes: nothing; the window's root carries AURA's package, which [WindowRootSelection]
 *   already drops from the UI tree.
 */
internal class AgentCursorOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: CursorView? = null
    private var motion: ValueAnimator? = null
    private var trailFade: ValueAnimator? = null
    private var squish: ValueAnimator? = null
    private val detachRunnable = Runnable { if (!runActive) detach() }

    /** True while a run owns the cursor and the user has not paused it. */
    @Volatile
    private var runActive = false

    /** True while the arrow window exists. Read off the main thread to skip a capture wait. */
    @Volatile
    var isShowing = false
        private set

    /** Follow the run and the step line. Call once, from the service's connect. */
    fun start() {
        scope.launch {
            val lock = ControlLockStore.shared
            combine(lock.localRunning, RemoteRun.shared.active, lock.pausedByHuman) { local, remote, paused ->
                (local || remote) to paused
            }.collect { (running, paused) -> onRun(running, paused) }
        }
        scope.launch { StepPill.line.collect { text -> view?.setLine(text) } }
    }

    private fun onRun(running: Boolean, paused: Boolean) {
        val active = running && !paused
        runActive = active
        if (!running) StepPill.clear()
        if (active) {
            handler.removeCallbacks(detachRunnable)
            val fresh = view == null
            val v = attach() ?: return
            if (fresh) v.parkAtCentre()
            v.setLine(StepPill.line.value)
            v.visibility = View.VISIBLE
        } else {
            detach()
        }
    }

    /** Glide the arrow so its tip lands on ([x], [y]), then squish once: a tap. */
    fun pointAt(x: Float, y: Float): Unit = onMain {
        val fresh = view == null
        val v = attach() ?: return@onMain
        val d = service.resources.displayMetrics.density
        val from = if (fresh) (x - ENTRY_DX_DP * d) to (y - ENTRY_DY_DP * d) else v.cx to v.cy
        run(v, listOf(from, x to y), GLIDE_MS, 0L, trail = false, tapAtEnd = true)
    }

    /** Ride [points] (screen pixels) over [durationMs], after [delayMs] parked on the first point. */
    fun trace(points: List<Pair<Float, Float>>, durationMs: Long, delayMs: Long = 0L): Unit = onMain {
        if (points.isEmpty()) return@onMain
        val v = attach() ?: return@onMain
        v.cx = points[0].first
        v.cy = points[0].second
        run(v, points, durationMs.coerceAtLeast(1L), delayMs.coerceAtLeast(0L), trail = true, tapAtEnd = false)
    }

    /** Main thread only. Hides the arrow and its pill; returns false when there was nothing to hide. */
    fun hideForCapture(): Boolean {
        val v = view ?: return false
        v.visibility = View.INVISIBLE
        return true
    }

    /** Main thread only. Undoes [hideForCapture]; safe if the window has gone meanwhile. */
    fun restoreAfterCapture() {
        view?.visibility = View.VISIBLE
    }

    /** Removes the window. Safe without a prior move. */
    fun detach(): Unit = onMain {
        handler.removeCallbacks(detachRunnable)
        motion?.cancel()
        trailFade?.cancel()
        squish?.cancel()
        val v = view ?: return@onMain
        view = null
        isShowing = false
        v.stopFloat()
        runCatching { service.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    /** Detach and stop following the run; call on service unbind. */
    fun release() {
        scope.cancel()
        detach()
    }

    private fun run(
        v: CursorView,
        points: List<Pair<Float, Float>>,
        durationMs: Long,
        delayMs: Long,
        trail: Boolean,
        tapAtEnd: Boolean,
    ) {
        motion?.cancel()
        trailFade?.cancel()
        handler.removeCallbacks(detachRunnable)
        v.trail = if (trail) Path().apply { moveTo(points[0].first, points[0].second) } else null
        v.trailAlpha = 1f
        v.moving = true
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = if (trail) LinearInterpolator() else GLIDE_EASE
            addUpdateListener {
                val (x, y) = pointAlong(points, it.animatedFraction)
                v.cx = x
                v.cy = y
                v.trail?.lineTo(x, y)
                v.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.moving = false
                    if (v.trail != null) fadeTrail(v)
                    if (tapAtEnd) squish(v)
                    if (!runActive) handler.postDelayed(detachRunnable, IDLE_MS)
                }
            })
            start()
        }
    }

    private fun squish(v: CursorView) {
        squish?.cancel()
        squish = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SQUISH_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                // Down to SQUISH_MIN in the first third, back to full size after.
                v.scale = if (f < 0.33f) 1f - (1f - SQUISH_MIN) * (f / 0.33f) else SQUISH_MIN + (1f - SQUISH_MIN) * ((f - 0.33f) / 0.67f)
                v.invalidate()
            }
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
            v.startFloat()
            v
        }.onFailure { Log.w(TAG, "cursor overlay refused (non-fatal): ${it.message}") }.getOrNull()
    }

    private fun onMain(block: () -> Unit) {
        val safe = { runCatching(block).onFailure { Log.w(TAG, "cursor failed (non-fatal): ${it.message}") } }
        if (Looper.myLooper() == Looper.getMainLooper()) safe() else handler.post { safe() }
    }

    /** The arrow, its swipe trail and the step pill; the arrow's tip sits on ([cx], [cy]). */
    private inner class CursorView : View(service) {
        var cx = 0f
        var cy = 0f
        var trail: Path? = null
        var trailAlpha = 1f
        var scale = 1f
        var moving = false

        private val density = resources.displayMetrics.density
        private val arrow = CursorShape.path()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            setShadowLayer(3f * density, 0f, 1f * density, 0x66000000)
        }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = CursorShape.EDGE_UNITS
            color = INK
        }
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f * density
            color = Color.WHITE
        }
        private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PILL_FILL }
        private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = PILL_EDGE
        }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = PILL_TEXT_SP * resources.displayMetrics.scaledDensity
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val pillRect = RectF()
        private var line = ""
        private var shown = ""
        private var lineAlpha = 1f
        private var floatPhase = 0f
        private var floatAmount = 1f
        private var floatAnim: ValueAnimator? = null
        private var lineFade: ValueAnimator? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setLayerType(LAYER_TYPE_SOFTWARE, null) // the shadow layer needs it
        }

        fun parkAtCentre() {
            val m = resources.displayMetrics
            cx = m.widthPixels * 0.5f
            cy = m.heightPixels * 0.42f
        }

        fun setLine(text: String) {
            if (text == line) return
            line = text
            lineFade?.cancel()
            lineFade = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = LINE_FADE_MS
                addUpdateListener {
                    lineAlpha = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            shown = text
        }

        fun startFloat() {
            if (floatAnim != null) return
            floatAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FLOAT_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    floatPhase = it.animatedValue as Float
                    // Ease the float out while travelling or tapping, so the tip is exact there.
                    val target = if (moving || scale < 1f) 0f else 1f
                    floatAmount += (target - floatAmount) * 0.12f
                    if (visibility == VISIBLE) invalidate()
                }
                start()
            }
        }

        fun stopFloat() {
            floatAnim?.cancel()
            floatAnim = null
            lineFade?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            trail?.let {
                trailPaint.alpha = (TRAIL_MAX_ALPHA * trailAlpha).toInt()
                canvas.drawPath(it, trailPaint)
            }
            val t = (floatPhase * 2 * PI).toFloat()
            val dx = sin(t) * FLOAT_SWAY_DP * density * floatAmount
            val dy = sin(2 * t) * FLOAT_BOB_DP * density * floatAmount
            val tilt = sin(2 * t) * FLOAT_TILT_DEG * floatAmount
            val x = cx + dx
            val y = cy + dy

            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(tilt)
            val k = CursorShape.unitScale(density) * scale
            canvas.scale(k, k)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, ink)
            canvas.restore()

            if (shown.isNotBlank()) drawPill(canvas, x, y)
        }

        /** Under the tip, flipped left or above when it would leave the screen. */
        private fun drawPill(canvas: Canvas, x: Float, y: Float) {
            val padH = 9f * density
            val padV = 5.5f * density
            val maxText = PILL_MAX_DP * density
            val text = TextUtils.ellipsize(shown, textPaint, maxText, TextUtils.TruncateAt.END).toString()
            val textW = textPaint.measureText(text)
            val fm = textPaint.fontMetrics
            val h = (fm.descent - fm.ascent) + padV * 2
            val w = textW + padH * 2
            val screenW = width.toFloat()
            val screenH = height.toFloat()
            var left = x + PILL_DX_DP * density
            var top = y + PILL_DY_DP * density
            if (left + w > screenW - EDGE_DP * density) left = (x - w + 4f * density).coerceAtLeast(EDGE_DP * density)
            if (top + h > screenH - BOTTOM_CLEAR_DP * density) top = y - h - 10f * density
            pillRect.set(left, top, left + w, top + h)
            val a = (lineAlpha * 255).toInt()
            pillFill.alpha = (Color.alpha(PILL_FILL) * lineAlpha).toInt()
            pillEdge.alpha = (Color.alpha(PILL_EDGE) * lineAlpha).toInt()
            textPaint.alpha = a
            val r = h / 2
            canvas.drawRoundRect(pillRect, r, r, pillFill)
            canvas.drawRoundRect(pillRect, r, r, pillEdge)
            canvas.drawText(text, left + padH, top + padV - fm.ascent, textPaint)
        }
    }

    private companion object {
        const val TAG = "AgentCursor"
        const val GLIDE_MS = 240L
        const val IDLE_MS = 3_000L
        const val TRAIL_FADE_MS = 450L
        const val TRAIL_MAX_ALPHA = 200
        const val SQUISH_MS = 260L
        const val SQUISH_MIN = 0.82f
        const val LINE_FADE_MS = 160L
        const val FLOAT_MS = 4_000L
        const val FLOAT_SWAY_DP = 1.1f
        const val FLOAT_BOB_DP = 1.3f
        const val FLOAT_TILT_DEG = 2.5f
        const val ENTRY_DX_DP = 60f
        const val ENTRY_DY_DP = 90f
        const val PILL_TEXT_SP = 12f
        const val PILL_MAX_DP = 230f
        const val PILL_DX_DP = 8f
        const val PILL_DY_DP = 20f
        const val EDGE_DP = 8f
        /** Keeps the pill above the pause button and the bottom sheet. */
        const val BOTTOM_CLEAR_DP = 120f
        const val INK = 0xFF0A0A0A.toInt()
        const val PILL_FILL = 0xE6141518.toInt()
        const val PILL_EDGE = 0x2EFFFFFF
        val GLIDE_EASE = PathInterpolator(0.3f, 0.7f, 0.2f, 1f)
    }
}

/**
 * cua-driver's default agent-cursor arrow (`CURSOR_PATH` in its theme builder, a 128-unit canvas),
 * with each vertex's tangents written as cubic curves. Moved so the tip is the origin.
 */
internal object CursorShape {
    private const val TIP_X = 45.5f
    private const val TIP_Y = 33.5f

    /** Units from the tip's top to the tail's bottom (y 30 → 106). */
    private const val HEIGHT_UNITS = 76f

    /** The arrow's on-screen height. */
    const val HEIGHT_DP = 20f

    /** A ~1 dp edge, in path units. */
    const val EDGE_UNITS = HEIGHT_UNITS / HEIGHT_DP * 1.1f

    fun unitScale(density: Float): Float = HEIGHT_DP * density / HEIGHT_UNITS

    fun path(): Path = Path().apply {
        fun x(v: Float) = v - TIP_X
        fun y(v: Float) = v - TIP_Y
        moveTo(x(55f), y(30f))
        cubicTo(x(48f), y(28f), x(42f), y(33f), x(43f), y(41f))
        lineTo(x(64f), y(98f))
        cubicTo(x(67f), y(106f), x(73f), y(106f), x(77f), y(99f))
        lineTo(x(86f), y(79f))
        cubicTo(x(88f), y(75f), x(91f), y(72f), x(95f), y(70f))
        lineTo(x(108f), y(63f))
        cubicTo(x(115f), y(59f), x(114f), y(53f), x(107f), y(50f))
        close()
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
