package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.TimeInterpolator
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
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.aura.aura_ui.presentation.utils.HapticUtils
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
 * AURA's hands on screen: a small white arrow (cua-driver's default arrow shape) that glides on an
 * arc to each touch, taps with a squish, a ring and a click haptic, presses and drags for a swipe,
 * and carries a pill under it saying what the step is for ([StepPill.line]).
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
    private var ripple: ValueAnimator? = null
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
            if (fresh) {
                v.parkAtCentre()
                aimX = v.cx
                aimY = v.cy
            }
            v.setLine(StepPill.line.value)
            v.visibility = View.VISIBLE
        } else {
            detach()
        }
    }

    /**
     * Glide the arrow on a curve so its tip lands on ([x], [y]), then tap: squish, ring, click haptic.
     *
     * - Contract: returns the glide's length in ms, computed on the calling thread. A caller that
     *   waits that long before injecting the tap makes the touch land as the arrow arrives.
     */
    fun pointAt(x: Float, y: Float): Long {
        val ms = glideMs(x, y)
        onMain {
            val v = attach() ?: return@onMain
            run(v, glidePath(v, x, y), ms, 0L, trail = false, onArrive = { tap(v, x, y) })
        }
        return ms
    }

    /**
     * Glide to the first of [points], press down (ring, tick haptic), stay [delayMs] there, ride
     * [points] over [durationMs] with a trail, then lift.
     *
     * - Contract: returns the glide's length in ms; the drag starts after it, so a caller that
     *   waits that long before injecting keeps finger and arrow together.
     */
    fun trace(points: List<Pair<Float, Float>>, durationMs: Long, delayMs: Long = 0L): Long {
        if (points.isEmpty()) return 0L
        val (sx, sy) = points[0]
        val ms = glideMs(sx, sy)
        onMain {
            val v = attach() ?: return@onMain
            val drag = {
                press(v, sx, sy)
                run(v, points, durationMs.coerceAtLeast(1L), delayMs.coerceAtLeast(0L), trail = true, onArrive = { lift(v) })
            }
            if (ms == 0L) drag() else run(v, glidePath(v, sx, sy), ms, 0L, trail = false, onArrive = drag)
        }
        return ms
    }

    /** Where the arrow is heading, so the next glide's length is known off the main thread. */
    @Volatile
    private var aimX = Float.NaN

    @Volatile
    private var aimY = Float.NaN

    /** Longer for longer trips, as a hand moves; 0 when the arrow is already there. */
    private fun glideMs(x: Float, y: Float): Long {
        val d = service.resources.displayMetrics.density
        val fromX = if (aimX.isNaN()) x - ENTRY_DX_DP * d else aimX
        val fromY = if (aimY.isNaN()) y - ENTRY_DY_DP * d else aimY
        aimX = x
        aimY = y
        val dp = hypot(x - fromX, y - fromY) / d
        if (dp < 4f) return 0L
        return (GLIDE_MIN_MS + dp * GLIDE_MS_PER_DP).toLong().coerceAtMost(GLIDE_MAX_MS)
    }

    /** A gentle arc from the arrow to ([x], [y]): a quadratic curve bowed upward, sampled. */
    private fun glidePath(v: CursorView, x: Float, y: Float): List<Pair<Float, Float>> {
        val d = service.resources.displayMetrics.density
        if (!v.placed) {
            v.cx = x - ENTRY_DX_DP * d
            v.cy = y - ENTRY_DY_DP * d
            v.placed = true
        }
        val fx = v.cx
        val fy = v.cy
        val len = hypot(x - fx, y - fy)
        if (len < 1f) return listOf(x to y)
        val bend = (len * ARC_BEND).coerceAtMost(ARC_MAX_DP * d)
        // Perpendicular to the trip, always bowed upward so every arc reads the same way.
        var px = -(y - fy) / len
        var py = (x - fx) / len
        if (py > 0f) {
            px = -px
            py = -py
        }
        val qx = (fx + x) / 2 + px * bend
        val qy = (fy + y) / 2 + py * bend
        return (0..ARC_SAMPLES).map { i ->
            val t = i / ARC_SAMPLES.toFloat()
            val u = 1 - t
            (u * u * fx + 2 * u * t * qx + t * t * x) to (u * u * fy + 2 * u * t * qy + t * t * y)
        }
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
        ripple?.cancel()
        aimX = Float.NaN
        aimY = Float.NaN
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
        onArrive: () -> Unit = {},
    ) {
        motion?.cancel()
        trailFade?.cancel()
        handler.removeCallbacks(detachRunnable)
        v.trail = if (trail) Path().apply { moveTo(points[0].first, points[0].second) } else null
        if (trail) {
            v.cx = points[0].first
            v.cy = points[0].second
        }
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
                    runCatching(onArrive)
                    if (!runActive) handler.postDelayed(detachRunnable, IDLE_MS)
                }
            })
            start()
        }
    }

    /** A tap: the arrow squishes and springs back, a ring spreads from the tip, a click haptic. */
    private fun tap(v: CursorView, x: Float, y: Float) {
        HapticUtils.performAgentTouchHaptic(service, tap = true)
        ripple(v, x, y, RIPPLE_TAP_DP)
        scaleTo(v, SQUISH_MIN, SQUISH_DOWN_MS) { scaleTo(v, 1f, SQUISH_UP_MS, OVERSHOOT) }
    }

    /** A swipe presses down: a small ring, a tick haptic, and the arrow stays pressed while it drags. */
    private fun press(v: CursorView, x: Float, y: Float) {
        HapticUtils.performAgentTouchHaptic(service, tap = false)
        ripple(v, x, y, RIPPLE_PRESS_DP)
        scaleTo(v, PRESSED, SQUISH_DOWN_MS)
    }

    /** The swipe lifts: the arrow springs back to full size. */
    private fun lift(v: CursorView) = scaleTo(v, 1f, SQUISH_UP_MS, OVERSHOOT)

    private fun scaleTo(
        v: CursorView,
        target: Float,
        ms: Long,
        ease: TimeInterpolator = DecelerateInterpolator(),
        then: () -> Unit = {},
    ) {
        squish?.cancel()
        squish = ValueAnimator.ofFloat(v.scale, target).apply {
            duration = ms
            interpolator = ease
            addUpdateListener {
                v.scale = it.animatedValue as Float
                v.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) then()
                }
            })
            start()
        }
    }

    /** A white ring that spreads from ([x], [y]) to [radiusDp] and fades. */
    private fun ripple(v: CursorView, x: Float, y: Float, radiusDp: Float) {
        ripple?.cancel()
        val maxR = radiusDp * service.resources.displayMetrics.density
        v.rippleX = x
        v.rippleY = y
        ripple = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RIPPLE_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                v.rippleR = maxR * f
                v.rippleAlpha = 1f - f
                v.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.rippleAlpha = 0f
                    v.invalidate()
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

        /** False until the arrow has a position; the first glide then enters from up-left of its target. */
        var placed = false
        var rippleX = 0f
        var rippleY = 0f
        var rippleR = 0f
        var rippleAlpha = 0f
        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = Color.WHITE
            setShadowLayer(2f * resources.displayMetrics.density, 0f, 0f, 0x55000000)
        }

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
            placed = true
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
            if (rippleAlpha > 0f) {
                ripplePaint.alpha = (RIPPLE_MAX_ALPHA * rippleAlpha).toInt()
                canvas.drawCircle(rippleX, rippleY, rippleR, ripplePaint)
            }
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
        const val GLIDE_MIN_MS = 180L
        const val GLIDE_MS_PER_DP = 0.55f
        const val GLIDE_MAX_MS = 520L
        const val ARC_BEND = 0.18f
        const val ARC_MAX_DP = 70f
        const val ARC_SAMPLES = 24
        const val IDLE_MS = 3_000L
        const val TRAIL_FADE_MS = 450L
        const val TRAIL_MAX_ALPHA = 200
        const val SQUISH_DOWN_MS = 90L
        const val SQUISH_UP_MS = 260L
        const val SQUISH_MIN = 0.78f
        const val PRESSED = 0.86f
        const val RIPPLE_MS = 420L
        const val RIPPLE_TAP_DP = 26f
        const val RIPPLE_PRESS_DP = 18f
        const val RIPPLE_MAX_ALPHA = 230
        val OVERSHOOT = OvershootInterpolator(2.2f)
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
