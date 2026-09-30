package com.aura.aura_ui.presentation.overlay

import android.content.Context
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
            Kind.WORK -> {
                val r = s * 0.07f
                canvas.drawCircle(s * 0.26f, c, r, fill)
                canvas.drawCircle(c, c, r, fill)
                canvas.drawCircle(s * 0.74f, c, r, fill)
            }
        }
    }

    private companion object {
        const val SIZE_DP = 16
    }
}
