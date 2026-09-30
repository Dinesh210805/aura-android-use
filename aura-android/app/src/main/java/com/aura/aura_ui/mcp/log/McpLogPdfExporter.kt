package com.aura.aura_ui.mcp.log

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

/**
 * Phase 10B — render a [SessionLog] as a multi-page PDF.
 *
 * Output goes to the public Downloads folder under `AURA logs/` so the
 * user can share it from any file manager. On Android 10+ this uses the
 * legacy storage path because we don't need full MediaStore semantics
 * for a single PDF write — the AURA app already has WRITE_EXTERNAL.
 *
 * Page layout (US Letter, 72 DPI):
 *   - Header with agent + timestamp + session id + reason
 *   - One block per invocation: tool name, args (mono), output preview,
 *     and a scaled embedded screenshot when present.
 */
class McpLogPdfExporter(private val context: Context) {

    private val store = McpSessionStore(context)
    private val dateFormat: DateFormat =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    fun export(sessionId: String): File? {
        val session = store.readSession(sessionId) ?: return null
        val pdf = PdfDocument()
        try {
            renderInto(pdf, session)
            val outFile = targetFile(session)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
            Log.i(TAG, "Exported PDF: ${outFile.absolutePath}")
            return outFile
        } finally {
            pdf.close()
        }
    }

    private fun targetFile(session: SessionLog): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AURA logs")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AURA logs")
        }
        dir.mkdirs()
        val safeStamp = session.startedAtMillis.toString()
        return File(dir, "aura-session-$safeStamp.pdf")
    }

    private fun renderInto(pdf: PdfDocument, session: SessionLog) {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        var page = pdf.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var y = MARGIN.toFloat()

        // ── Header ────────────────────────────────────────────────
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            isAntiAlias = true
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 11f
            isAntiAlias = true
        }
        val monoPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            isAntiAlias = true
        }
        val divider = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }

        canvas.drawText("AURA MCP session log", MARGIN.toFloat(), y, titlePaint); y += 24
        canvas.drawText("Started: ${dateFormat.format(Date(session.startedAtMillis))}", MARGIN.toFloat(), y, bodyPaint); y += 14
        canvas.drawText("Agent: ${session.agentLabel ?: "(unknown)"}", MARGIN.toFloat(), y, bodyPaint); y += 14
        canvas.drawText("Session: ${session.sessionId}", MARGIN.toFloat(), y, bodyPaint); y += 14
        session.endReason?.let {
            canvas.drawText("End reason: $it", MARGIN.toFloat(), y, bodyPaint); y += 14
        }
        canvas.drawLine(MARGIN.toFloat(), y + 4, (PAGE_WIDTH - MARGIN).toFloat(), y + 4, divider); y += 18

        // ── The conversation, if this session had one ─────────────
        // This exporter walks its own blocks rather than the HTML renderer's, so a Live
        // session exported to PDF was a header and nothing else until utterances were
        // added here too.
        for (u in session.utterances) {
            val who = when (u.speaker) {
                "user" -> "You"
                "aura" -> "AURA"
                else -> "System"
            }
            val suffix = if (u.kind == "speech") "" else "  [${u.kind}]"
            val lines = wrappedLines("$who$suffix: ${u.text}", 105)
            for (line in lines) {
                if (y + 13 > PAGE_HEIGHT - MARGIN) {
                    pdf.finishPage(page)
                    page = pdf.startPage(pageInfo)
                    canvas = page.canvas
                    y = MARGIN.toFloat()
                }
                canvas.drawText(line, MARGIN.toFloat(), y, monoPaint); y += 13
            }
            y += 3
        }
        if (session.utterances.isNotEmpty()) {
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y, divider); y += 12
        }

        // ── One block per invocation ──────────────────────────────
        for (inv in session.invocations) {
            val needed = estimatedBlockHeight(inv)
            if (y + needed > PAGE_HEIGHT - MARGIN) {
                pdf.finishPage(page)
                page = pdf.startPage(pageInfo)
                canvas = page.canvas
                y = MARGIN.toFloat()
            }

            canvas.drawText(
                "#${inv.index + 1}  ${inv.toolName}  ·  ${dateFormat.format(Date(inv.timestampMillis))}  ·  ${inv.durationMs}ms  ·  ${if (inv.success) "OK" else "ERR"}",
                MARGIN.toFloat(), y, titlePaint.copy(size = 13f),
            )
            y += 16
            inv.argsJson?.takeIf { it.isNotBlank() }?.let { args ->
                canvas.drawText("args: $args".take(120), MARGIN.toFloat(), y, monoPaint); y += 14
            }
            inv.outputSummary.takeIf { it.isNotBlank() }?.let { out ->
                wrappedLines(out, 110).take(3).forEach { line ->
                    canvas.drawText(line, MARGIN.toFloat(), y, monoPaint); y += 13
                }
            }
            if (inv.hasScreenshot) {
                val file = store.screenshotFile(session.sessionId, inv.index)
                if (file != null && file.exists()) {
                    val bm = BitmapFactory.decodeFile(file.absolutePath)
                    if (bm != null) {
                        val targetW = (PAGE_WIDTH - MARGIN * 2).toFloat() * 0.55f
                        val scale = targetW / bm.width
                        val targetH = bm.height * scale
                        if (y + targetH > PAGE_HEIGHT - MARGIN) {
                            pdf.finishPage(page)
                            page = pdf.startPage(pageInfo)
                            canvas = page.canvas
                            y = MARGIN.toFloat()
                        }
                        val dest = android.graphics.RectF(
                            MARGIN.toFloat(), y, MARGIN + targetW, y + targetH,
                        )
                        canvas.drawBitmap(bm, null, dest, null)
                        y += targetH + 8
                    }
                }
            }
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y, divider); y += 12
        }

        pdf.finishPage(page)
    }

    private fun estimatedBlockHeight(inv: ToolInvocation): Float {
        var h = 16f + 14f + 13f * 3 + 12f
        if (inv.hasScreenshot) h += 320f // worst-case scaled image
        return h
    }

    private fun wrappedLines(text: String, perLine: Int): List<String> {
        if (text.length <= perLine) return listOf(text)
        return text.chunked(perLine)
    }

    private fun Paint.copy(size: Float): Paint = Paint(this).apply { textSize = size }

    companion object {
        private const val TAG = "McpLogPdfExport"
        // US Letter @ 72 DPI
        private const val PAGE_WIDTH = 612
        private const val PAGE_HEIGHT = 792
        private const val MARGIN = 36
    }
}
