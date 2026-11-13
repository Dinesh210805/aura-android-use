package com.aura.aura_ui.presentation.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.aura.aura_ui.services.AgentStatusRegistry.Kind

/**
 * The small mark in front of each status-strip line, one per [Kind], drawn with strokes.
 *
 * Drawn, not typed: glyphs like ⌕ ⟲ ❖ render as empty boxes in several OEM fonts (ColorOS,
 * MIUI), and a strip with a tofu box in it looks broken. Twelve short strokes cannot go missing.
 * Monochrome except [Kind.HOLD], which is the one moment the strip may use the attention colour.
 */
internal class BeatIconView(context: Context, private val ink: Int, private val alert: Int) : View(context) {

    var kind: Kind = Kind.WORK
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = context.resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val oval = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val color = if (kind == Kind.HOLD) alert else ink
        stroke.color = color
        fill.color = color
        val s = width.toFloat()
        val c = s / 2
        path.reset()
        when (kind) {
            // A screen: rounded frame.
            Kind.LOOK -> {
                oval.set(s * 0.28f, s * 0.12f, s * 0.72f, s * 0.88f)
                canvas.drawRoundRect(oval, s * 0.08f, s * 0.08f, stroke)
                canvas.drawLine(s * 0.44f, s * 0.76f, s * 0.56f, s * 0.76f, stroke)
            }
            // A touch: dot inside a ring.
            Kind.ACT -> {
                canvas.drawCircle(c, c, s * 0.13f, fill)
                canvas.drawCircle(c, c, s * 0.32f, stroke)
            }
            // A text cursor.
            Kind.TYPE -> {
                canvas.drawLine(c, s * 0.2f, c, s * 0.8f, stroke)
                canvas.drawLine(s * 0.38f, s * 0.2f, s * 0.62f, s * 0.2f, stroke)
                canvas.drawLine(s * 0.38f, s * 0.8f, s * 0.62f, s * 0.8f, stroke)
            }
            // Going somewhere: arrow.
            Kind.NAV -> {
                canvas.drawLine(s * 0.2f, c, s * 0.78f, c, stroke)
                path.moveTo(s * 0.56f, s * 0.3f)
                path.lineTo(s * 0.78f, c)
                path.lineTo(s * 0.56f, s * 0.7f)
                canvas.drawPath(path, stroke)
            }
            // Looking something up: magnifier.
            Kind.SEARCH -> {
                canvas.drawCircle(s * 0.44f, s * 0.44f, s * 0.22f, stroke)
                canvas.drawLine(s * 0.6f, s * 0.6f, s * 0.8f, s * 0.8f, stroke)
            }
            // Finished: check.
            Kind.DONE -> {
                path.moveTo(s * 0.2f, s * 0.52f)
                path.lineTo(s * 0.42f, s * 0.72f)
                path.lineTo(s * 0.8f, s * 0.3f)
                canvas.drawPath(path, stroke)
            }
            // Trying again: open circle with an arrowhead.
            Kind.RECOVER -> {
                oval.set(s * 0.22f, s * 0.22f, s * 0.78f, s * 0.78f)
                canvas.drawArc(oval, -60f, 290f, false, stroke)
                path.moveTo(s * 0.62f, s * 0.14f)
                path.lineTo(s * 0.66f, s * 0.3f)
                path.lineTo(s * 0.5f, s * 0.32f)
                canvas.drawPath(path, stroke)
            }
            // Knowledge: an open book.
            Kind.LEARN -> {
                path.moveTo(c, s * 0.3f)
                path.lineTo(s * 0.18f, s * 0.24f)
                path.lineTo(s * 0.18f, s * 0.74f)
                path.lineTo(c, s * 0.8f)
                path.lineTo(s * 0.82f, s * 0.74f)
                path.lineTo(s * 0.82f, s * 0.24f)
                path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(c, s * 0.3f, c, s * 0.8f, stroke)
            }
            // Needs you / stopped for safety: solid square.
            Kind.HOLD -> {
                oval.set(s * 0.26f, s * 0.26f, s * 0.74f, s * 0.74f)
                canvas.drawRoundRect(oval, s * 0.06f, s * 0.06f, fill)
            }
            // Busy: three dots.
            Kind.WORK -> drawBusyDots(canvas, s, c)
        }
    }

    /**
     * Three dots in a slow wave: each one rises a little and brightens in turn.
     *
     * - Contract: redraws itself every frame only while [kind] is [Kind.WORK] and the view is on
     *   screen; any other kind, a hidden view or "Remove animations" draws still dots.
     * - Why so small (rise of 12% of the icon, 1.2 s loop): it sits beside a line of text and must
     *   read as "alive", not pull the eye off the words.
     */
    private fun drawBusyDots(canvas: Canvas, s: Float, c: Float) {
        val r = s * 0.075f
        val animate = isShown && ValueAnimator.areAnimatorsEnabled()
        val t = if (animate) (SystemClock.uptimeMillis() % LOOP_MS) / LOOP_MS.toFloat() else -1f
        val baseAlpha = fill.alpha
        DOT_X.forEachIndexed { i, x ->
            // 0..1 bump that travels across the dots; flat for the rest of the loop.
            val phase = if (t < 0f) 0f else ((t - i * DOT_STAGGER + 1f) % 1f)
            val bump = if (phase < BUMP_SPAN) sin(phase / BUMP_SPAN * PI).toFloat() else 0f
            fill.alpha = (baseAlpha * (MIN_ALPHA + (1f - MIN_ALPHA) * (if (t < 0f) 1f else bump))).toInt()
            canvas.drawCircle(s * x, c - bump * s * RISE, r, fill)
        }
        fill.alpha = baseAlpha
        if (animate) postInvalidateOnAnimation()
    }

    /** Restart the wave when the strip comes back (it is hidden for every screenshot). */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && kind == Kind.WORK) invalidate()
    }

    private companion object {
        const val SIZE_DP = 16
        const val LOOP_MS = 1_200L
        const val DOT_STAGGER = 0.15f
        const val BUMP_SPAN = 0.45f
        const val RISE = 0.12f
        const val MIN_ALPHA = 0.4f
        val DOT_X = floatArrayOf(0.26f, 0.5f, 0.74f)
    }
}
