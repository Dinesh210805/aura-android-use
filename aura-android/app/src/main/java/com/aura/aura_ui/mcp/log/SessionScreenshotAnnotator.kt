package com.aura.aura_ui.mcp.log

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File

/**
 * Burns a gesture marker onto a copy of a tool's screenshot so the rendered log
 * shows *where the agent acted* — a tap crosshair+ring, or a swipe arrow — the
 * Android-Canvas equivalent of the Python logger's OpenCV annotator.
 *
 * The raw `screenshots/<idx>.<ext>` is never modified (it is the audit ground
 * truth); annotated copies go to `annotated/<idx>.png` and are regenerated on
 * demand at render time.
 */
object SessionScreenshotAnnotator {

    private const val TAG = "SessionAnnotator"
    private val GREEN = Color.rgb(80, 220, 80)
    private val AMBER = Color.rgb(255, 170, 60)
    private val CYAN = Color.rgb(60, 200, 220)
    private val SHADOW = Color.argb(180, 10, 10, 10)

    /**
     * Produce `annotated/<index>.png` for [inv] if it has a gesture + coords + raw
     * screenshot. Returns the annotated file, or null if nothing to draw.
     */
    fun annotate(sessionDir: File, inv: ToolInvocation): File? {
        val gesture = inv.gestureType ?: return null
        if (!inv.hasScreenshot) return null
        val raw = rawScreenshotFile(sessionDir, inv.index) ?: return null
        val src = runCatching { BitmapFactory.decodeFile(raw.absolutePath) }.getOrNull() ?: return null

        return runCatching {
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(bmp)
            when (gesture) {
                "swipe", "scroll" -> drawSwipe(canvas, inv)
                else -> drawTap(canvas, inv, gesture)
            }
            val outDir = File(sessionDir, "annotated").apply { mkdirs() }
            val out = File(outDir, "${inv.index}.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
            out
        }.onFailure { Log.w(TAG, "Annotation failed for invocation ${inv.index}", it) }.getOrNull()
    }

    private fun drawTap(canvas: Canvas, inv: ToolInvocation, gesture: String) {
        val x = inv.tapX?.toFloat() ?: return
        val y = inv.tapY?.toFloat() ?: return
        val color = if (gesture == "long_press") AMBER else GREEN
        val stroke = Paint().apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
        }
        val fill = Paint().apply {
            this.color = color; style = Paint.Style.FILL; isAntiAlias = true
        }
        // crosshair
        canvas.drawLine(x - 60f, y, x + 60f, y, stroke)
        canvas.drawLine(x, y - 60f, x, y + 60f, stroke)
        canvas.drawCircle(x, y, 42f, stroke)
        canvas.drawCircle(x, y, 10f, fill)
        if (gesture == "double_tap") canvas.drawCircle(x, y, 60f, stroke)
        drawLabel(canvas, if (gesture == "long_press") "HOLD" else if (gesture == "double_tap") "x2 TAP" else "TAP", x + 50f, y - 16f, color)
    }

    private fun drawSwipe(canvas: Canvas, inv: ToolInvocation) {
        val x1 = inv.tapX?.toFloat() ?: return
        val y1 = inv.tapY?.toFloat() ?: return
        val x2 = inv.tapX2?.toFloat() ?: (x1)
        val y2 = inv.tapY2?.toFloat() ?: (y1)
        val line = Paint().apply {
            color = CYAN; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
        }
        val fill = Paint().apply { color = CYAN; style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawCircle(x1, y1, 14f, fill)
        canvas.drawLine(x1, y1, x2, y2, line)
        // arrow head
        val angle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val head = 28f
        for (a in listOf(angle - 0.4, angle + 0.4)) {
            canvas.drawLine(
                x2, y2,
                (x2 - head * Math.cos(a)).toFloat(),
                (y2 - head * Math.sin(a)).toFloat(),
                line,
            )
        }
        drawLabel(canvas, "SWIPE", (x1 + x2) / 2f, (y1 + y2) / 2f - 12f, CYAN)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int) {
        val tp = Paint().apply {
            this.color = color; textSize = 34f; isAntiAlias = true; isFakeBoldText = true
        }
        val bg = Paint().apply { this.color = SHADOW; style = Paint.Style.FILL }
        val w = tp.measureText(text)
        canvas.drawRect(x - 4f, y - 30f, x + w + 8f, y + 8f, bg)
        canvas.drawText(text, x, y, tp)
    }
}
