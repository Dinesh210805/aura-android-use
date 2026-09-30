package com.aura.aura_ui.mcp.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A text-SoM for the agent trace: an annotated screenshot with no pixels.
 *
 * A trace step for `read_screen` currently reads
 * `[[0,0,1240,120,"Back","*","Button"], …]` — 180 of those — from which no human can
 * picture the screen. This draws it: each element's box outlined on a character grid
 * with its som_id inside, and the labels tabulated underneath. Someone debugging a
 * mis-tap can now see what the agent was choosing between.
 *
 * Ported from the desktop viewer's `livescreen.py:render()`, keeping its two layout
 * rules — both of which exist because phone UI trees break naive drawing:
 *
 *  - **Only som_ids go on the canvas, never labels.** A label cannot overflow a box it
 *    was never drawn in. Names live in the table below.
 *  - **Elements with identical bounds collapse to one box tagged `N+k`.** Android
 *    produces these constantly (a row and its clickable wrapper share bounds exactly);
 *    drawn separately they would print one number on top of another.
 *
 * One deliberate divergence from the viewer: it re-sorts by area and renumbers 1..n for
 * its own display. **This must not.** The numbers here are the som_ids the agent
 * actually received, so the picture and the agent's choice can be compared. A renumbered
 * canvas would be worse than none — it would send someone debugging a mis-tap after the
 * wrong element.
 *
 * Trace-only. Nothing here is sent to the model; it costs no tokens.
 */
object ScreenCanvas {

    /** Portrait-ish default: character cells are about twice as tall as they are wide. */
    const val DEFAULT_COLS = 44
    const val DEFAULT_ROWS = 46

    /** An element, already resolved to the agent's som_id. */
    private data class Element(
        val somId: Int,
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val label: String,
        val flags: String,
        /** Centre-point payloads (`perceive_screen`) have no box to outline. */
        val pointOnly: Boolean,
    )

    /**
     * Render [payloadJson] — a `read_screen` frame or a `perceive_screen` result — as a
     * canvas plus a label table. Null when there is nothing to draw, so a caller never
     * shows an empty box that reads as "the screen was blank".
     */
    fun render(payloadJson: String, cols: Int = DEFAULT_COLS, rows: Int = DEFAULT_ROWS): String? {
        val root = lastJsonObject(payloadJson) ?: return null
        val elements = parseElements(root).ifEmpty { return null }

        // Full-screen frames are the root container and every scrim over it. Outlining
        // them just draws a border around the border.
        val screenW = maxOf(root.int("w"), elements.maxOf { it.x2 }).coerceAtLeast(1)
        val screenH = maxOf(root.int("h"), elements.maxOf { it.y2 }).coerceAtLeast(1)
        val frames = elements.count { !it.pointOnly && it.spansMostOf(screenW, screenH) }
        val body = elements.filterNot { !it.pointOnly && it.spansMostOf(screenW, screenH) }
        if (body.isEmpty()) return null

        val grid = Array(rows) { CharArray(cols) { ' ' } }
        val ink = Array(rows) { BooleanArray(cols) }

        fun gx(x: Int) = (x.toDouble() / screenW * (cols - 1)).toInt().coerceIn(0, cols - 1)
        fun gy(y: Int) = (y.toDouble() / screenH * (rows - 1)).toInt().coerceIn(0, rows - 1)

        // Group by identical bounds, preserving first-seen (i.e. som_id) order.
        val groups = LinkedHashMap<List<Int>, MutableList<Element>>()
        for (e in body) groups.getOrPut(listOf(e.x1, e.y1, e.x2, e.y2)) { mutableListOf() }.add(e)

        val undrawn = mutableListOf<Int>()
        for ((bounds, group) in groups) {
            val lead = group.first()
            val c1 = gx(bounds[0])
            val c2 = maxOf(gx(bounds[2]), c1)
            val r1 = gy(bounds[1])
            val r2 = maxOf(gy(bounds[3]), r1)

            if (!lead.pointOnly) outline(grid, c1, c2, r1, r2)

            val tag = if (group.size > 1) "${lead.somId}+${group.size - 1}" else "${lead.somId}"
            if (!place(grid, ink, tag, c1, c2, r1, r2, cols)) undrawn += lead.somId
        }

        return buildString {
            append(root.str("pkg").ifBlank { "?" })
            append("   ${screenW}x${screenH}px   ${body.size} elements in ${groups.size} boxes")
            if (frames > 0) append("   ($frames full-screen frame(s) not drawn)")
            appendLine()
            appendLine("numbers = som_id;  N+k = N plus k more sharing identical bounds")
            // ASCII, not Unicode box-drawing — see ScreenGrid.kt's build() for why: those
            // glyphs aren't guaranteed the same advance width as regular characters in
            // every monospace font, and Android's font fallback can route them through a
            // different font entirely, staggering the borders on-device.
            appendLine("+" + "-".repeat(cols) + "+")
            for (row in grid) appendLine("|" + String(row) + "|")
            append("+" + "-".repeat(cols) + "+")
            if (undrawn.isNotEmpty()) {
                append("\nnot drawn at this canvas size (listed below): $undrawn")
            }
            val labelled = body.filter { it.label.isNotBlank() }
            if (labelled.isNotEmpty()) {
                append("\n\nsom  flg   label")
                for (e in labelled) {
                    append("\n${e.somId.toString().padEnd(4)} ${e.flags.ifBlank { "-" }.padEnd(5)} ${e.label}")
                }
            }
        }
    }

    private fun Element.spansMostOf(w: Int, h: Int): Boolean =
        (x2 - x1) >= w * 0.98 && (y2 - y1) >= h * 0.98

    private fun outline(grid: Array<CharArray>, c1: Int, c2: Int, r1: Int, r2: Int) {
        for (c in c1..c2) for (r in listOf(r1, r2)) {
            if (grid[r][c] == ' ') grid[r][c] = if (r1 == r2) '.' else '-'
        }
        for (r in r1..r2) for (c in listOf(c1, c2)) {
            if (grid[r][c] == ' ') grid[r][c] = if (c1 == c2) '.' else '|'
        }
    }

    /**
     * Write [tag] somewhere inside the box without touching another tag.
     *
     * The adjacency check is the point: two numbers written back-to-back read as one
     * number ("1" beside "2" becomes "12"), which is a silent misidentification rather
     * than a visible collision. A blank cell is required on each side.
     */
    private fun place(
        grid: Array<CharArray>,
        ink: Array<BooleanArray>,
        tag: String,
        c1: Int,
        c2: Int,
        r1: Int,
        r2: Int,
        cols: Int,
    ): Boolean {
        val w = tag.length
        for (r in r1..r2) {
            var c = c1
            while (c <= c2) {
                if (c + w > cols) break
                val touchesLeft = c > 0 && ink[r][c - 1]
                val touchesRight = c + w < cols && ink[r][c + w]
                val overlaps = (0 until w).any { ink[r][c + it] }
                if (!touchesLeft && !touchesRight && !overlaps) {
                    for (k in tag.indices) {
                        grid[r][c + k] = tag[k]
                        ink[r][c + k] = true
                    }
                    return true
                }
                c++
            }
        }
        return false
    }

    /**
     * Parse `e` into elements, resolving som_id as the 1-based index — the invariant both
     * producers publish.
     *
     * Two positional shapes exist and the difference is not cosmetic:
     *  - `read_screen`     → `[x1, y1, x2, y2, label, flags, class]` (7 fields)
     *  - `perceive_screen` → `[cx, cy, name, flags]`                 (4 fields)
     *
     * Reading the second with the first's offsets would treat a centre point as a
     * bounding box and draw a rectangle from (cx, cy) to (name, flags).
     *
     * Degenerate boxes are skipped for DRAWING but still consume their som_id, because
     * the numbers must keep matching what the agent was handed.
     */
    private fun parseElements(root: JsonObject): List<Element> {
        val arr = root["e"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<Element>()
        arr.forEachIndexed { i, raw ->
            val row = raw as? JsonArray ?: return@forEachIndexed
            val somId = i + 1
            fun num(at: Int) = (row.getOrNull(at)?.jsonPrimitive?.intOrNull) ?: 0
            fun text(at: Int) = row.getOrNull(at)?.jsonPrimitive?.contentOrNull.orEmpty()
            if (row.size >= 7) {
                val x1 = num(0); val y1 = num(1); val x2 = num(2); val y2 = num(3)
                if (x2 <= x1 || y2 <= y1) return@forEachIndexed
                out += Element(somId, x1, y1, x2, y2, text(4), text(5), pointOnly = false)
            } else if (row.size >= 2) {
                val cx = num(0); val cy = num(1)
                out += Element(somId, cx, cy, cx, cy, text(2), text(3), pointOnly = true)
            }
        }
        return out
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0

    /**
     * The last line that parses as a JSON object.
     *
     * `perceive_screen` returns SEVERAL content blocks — a loading warning, a one-line
     * human label, then the payload — which the logger joins with newlines. Parsing the
     * whole string always throws. `ActionGuard` did exactly that for weeks: the throw was
     * swallowed and it silently fell back to a broken hash. Taking the last JSON line
     * works for both that shape and a bare `read_screen` frame.
     */
    private fun lastJsonObject(text: String): JsonObject? {
        fun parse(s: String) = runCatching { json.parseToJsonElement(s).jsonObject }.getOrNull()
        parse(text.trim())?.let { return it }
        for (line in text.lines().asReversed()) {
            val t = line.trim()
            if (t.startsWith("{") && t.endsWith("}")) parse(t)?.let { return it }
        }
        return null
    }
}
