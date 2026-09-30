package com.aura.mcp.tools

import com.aura.mcp.bridge.DetectedElement
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a screen as a character grid with each element's som_id **and label inside its
 * own box** — the representation `read_screen` hands the model.
 *
 * Ported from the desktop workbench `screenview.py` (`build` / `axis_map` / `agent_payload`),
 * where the layout rules below were derived against a live device with a token counter
 * attached. This is a faithful port, not a reinterpretation.
 *
 * ### Why a grid instead of a list of coordinates
 *
 * A coordinate list states position and hides *structure*. From `[40,420,600,900]` the
 * model has to infer that element 12 sits inside card 8 by comparing bounds pairwise
 * across every element on screen. A grid shows containment, adjacency and reading order
 * the way a screenshot does, and the [Row.parent] column states the nesting outright.
 *
 * ### The three drawing rules, in priority order
 *
 *  1. **Borders are never overwritten.** A box whose wall has been punched out by a
 *     neighbour's text is no longer a box, and the picture stops reading as structure.
 *     Text may only occupy an interior.
 *  2. **Text goes inside its own box**, word-wrapped over as many interior lines as it
 *     needs. It is text — wrapping is free, so there is no reason to clip
 *     "Notification settings" to "Notifi…" when the box is three rows tall.
 *  3. **Only if it cannot fit** may a label borrow adjacent empty space, still never
 *     touching a wall.
 *
 * [Rendered.inside] / [Rendered.total] report how many labels satisfied rule 2, so the
 * caller can grow the grid until nearly all of them do — the text equivalent of zooming.
 */
internal object ScreenGrid {

    /** Growth ceiling for the fit search. Beyond this the payload costs more than it says. */
    const val MAX_COLS = 100

    /** Start narrow; [fit] grows only while labels are still landing outside their boxes. */
    const val START_COLS = 48

    /** Stop growing once this share of labels sits inside its own box. */
    const val TARGET_FIT = 0.92

    /** One element as drawn: the som_id it was given, and what the table says about it. */
    data class Row(
        val somId: Int,
        /** som_id of the smallest element strictly containing this one; null at top level. */
        val parent: Int?,
        val flags: String,
        val label: String,
        /** True when the label is already drawn on the grid, so the table may omit it. */
        val labelOnGrid: Boolean,
        val element: DetectedElement,
    )

    data class Rendered(
        val canvas: String,
        val rows: List<Row>,
        val boxCount: Int,
        val frameCount: Int,
        val inside: Int,
        val total: Int,
        val cols: Int,
        val gridRows: Int,
        /**
         * som_ids that get a number on the grid: the lead of each drawn, targetable box.
         * `read_screen`'s annotated image draws exactly these, so picture and grid agree.
         */
        val marked: List<Int> = emptyList(),
    )

    /**
     * Render at the smallest grid on which [TARGET_FIT] of labels land inside their own
     * box, growing 35% at a time.
     *
     * Growing is cheap and being cramped is not: a label pushed outside its box is a
     * label the model may attach to the wrong element, which is a mis-tap rather than an
     * ugly diagram.
     */
    fun fit(elements: List<DetectedElement>): Rendered? {
        if (elements.isEmpty()) return null
        val screenW = elements.maxOf { it.bbox.x2 }.coerceAtLeast(1)
        val screenH = elements.maxOf { it.bbox.y2 }.coerceAtLeast(1)
        // A character cell is about twice as tall as it is wide, so the grid must be
        // twice as wide in cells as the screen is in pixels to keep the shape honest.
        val ratio = (screenW.toDouble() / screenH) * 2.0

        var cols = START_COLS
        var best: Rendered? = null
        while (cols <= MAX_COLS) {
            val gridRows = max(12, (cols / ratio).roundToInt())
            val out = build(elements, cols, gridRows) ?: return null
            best = out
            if (out.total > 0 && out.inside >= TARGET_FIT * out.total) break
            cols = (cols * 1.35).toInt()
        }
        return best
    }

    // ── the renderer ────────────────────────────────────────────────────────

    private class El(
        val src: DetectedElement,
        var somId: Int = 0,
        var name: String = "",
        var flags: String = "",
    ) {
        val x1 get() = src.bbox.x1
        val y1 get() = src.bbox.y1
        val x2 get() = src.bbox.x2
        val y2 get() = src.bbox.y2
        val area get() = (x2 - x1) * (y2 - y1)
        val label get() = src.label
    }

    /**
     * Worth a number on the canvas: you could tap it, scroll it, or read it.
     *
     * Everything else is layout scaffolding. Measured on a launcher home screen, 288 of
     * 312 nodes are pure wrappers — numbering them all left 55% of boxes with no room
     * for their id, while numbering only these leaves 0% undrawn. Wrappers are still
     * DRAWN (their outline is real structure) and still listed; they just do not compete
     * for ink.
     */
    private fun targetable(e: El): Boolean =
        e.label.isNotBlank() || e.src.interactive || e.src.scrollable || e.src.editable

    private fun build(elements: List<DetectedElement>, wantCols: Int, wantRows: Int): Rendered? {
        val all = elements.map { El(it) }
        val screenW = all.maxOf { it.x2 }.coerceAtLeast(1)
        val screenH = all.maxOf { it.y2 }.coerceAtLeast(1)

        val frames = all.filter { (it.x2 - it.x1) >= screenW * 0.98 && (it.y2 - it.y1) >= screenH * 0.98 }
        val body = all.filterNot { it in frames }
            .sortedWith(compareByDescending<El> { it.area }.thenBy { it.y1 }.thenBy { it.x1 })
        if (body.isEmpty()) return null

        body.forEachIndexed { i, e ->
            e.somId = i + 1
            e.flags = flagsOf(e.src)
            e.name = e.label.ifBlank { "<${e.src.elementType}>" }
        }

        // Group by identical bounds — a row and its clickable wrapper share them exactly,
        // and drawn separately they would print one number on top of another.
        val boxesAll = LinkedHashMap<List<Int>, MutableList<El>>()
        for (e in body) boxesAll.getOrPut(listOf(e.x1, e.y1, e.x2, e.y2)) { mutableListOf() }.add(e)
        for (g in boxesAll.values) g.sortWith(compareBy({ if (it.label.isNotBlank()) 0 else 1 }, { it.somId }))

        val parent = parents(body)

        // Collapse pass-through wrappers. A container that is not itself a target and
        // holds ONE child adds two border rows, two border columns and no information —
        // on a Gmail list that stacked four levels of `││   │   │` before any text. A
        // container with two or more children is a real GROUP (a row of buttons, a list)
        // and is kept, because its outline is the only thing saying they belong together.
        // Collapsed elements are not lost: still numbered, still in the table.
        val childCount = parent.values.filterNotNull().groupingBy { it }.eachCount()
        val boxes = LinkedHashMap<List<Int>, MutableList<El>>().apply {
            for ((k, g) in boxesAll) {
                val lead = g.first()
                if (targetable(lead) || (childCount[lead.somId] ?: 0) >= 2) put(k, g)
            }
        }.ifEmpty { boxesAll }

        // ── the non-linear axis mapping (see axis_map) ──
        val xs = (setOf(0, screenW) + body.map { it.x1 } + body.map { it.x2 }).sorted()
        val ys = (setOf(0, screenH) + body.map { it.y1 } + body.map { it.y2 }).sorted()
        val xDemand = HashMap<Int, Int>()
        val yDemand = HashMap<Int, Int>()
        for (e in body) {
            if (!targetable(e)) continue
            // A band that starts a labelled box must hold its border plus a line of text.
            xs.indexOf(e.x1).takeIf { it >= 0 }?.let { xDemand[it] = max(xDemand[it] ?: 1, 4) }
            ys.indexOf(e.y1).takeIf { it >= 0 }?.let { yDemand[it] = max(yDemand[it] ?: 1, 2) }
        }
        val xmap = axisMap(xs, screenW, wantCols - 1, xDemand, emptyCap = 4)
        val ymap = axisMap(ys, screenH, wantRows - 1, yDemand, emptyCap = 1)
        val cols = max(wantCols, (xmap.values.maxOrNull() ?: 0) + 2)
        val gridRows = max(wantRows, (ymap.values.maxOrNull() ?: 0) + 2)

        fun gx(x: Int) = (xmap[x] ?: (x.toDouble() / screenW * (cols - 1)).roundToInt()).coerceIn(0, cols - 1)
        fun gy(y: Int) = (ymap[y] ?: (y.toDouble() / screenH * (gridRows - 1)).roundToInt()).coerceIn(0, gridRows - 1)

        val grid = Array(gridRows) { CharArray(cols) { ' ' } }
        val border = Array(gridRows) { BooleanArray(cols) }
        val ink = Array(gridRows) { BooleanArray(cols) }

        // ── pass 1: outlines. Recorded in `border` so nothing may later draw over them.
        data class Geo(val group: List<El>, val c1: Int, val c2: Int, val r1: Int, val r2: Int)
        val geo = mutableListOf<Geo>()
        for ((b, g) in boxes) {
            val c1 = gx(b[0])
            val c2 = max(gx(b[2]), c1)
            val r1 = gy(b[1])
            val r2 = max(gy(b[3]), r1)
            geo += Geo(g, c1, c2, r1, r2)
            for (c in c1..c2) for (r in listOf(r1, r2)) {
                if (grid[r][c] == ' ') grid[r][c] = if (r1 == r2) '.' else '-'
                border[r][c] = true
            }
            for (r in r1..r2) for (c in listOf(c1, min(c2, cols - 1))) {
                if (grid[r][c] == ' ') grid[r][c] = if (c1 == c2) '.' else '|'
                border[r][c] = true
            }
        }

        fun writable(r: Int, c0: Int, n: Int): Boolean {
            if (r < 0 || r >= gridRows || c0 < 0 || c0 + n > cols) return false
            return (0 until n).none { border[r][c0 + it] || ink[r][c0 + it] }
        }

        fun emit(r: Int, c0: Int, text: String) {
            text.forEachIndexed { k, ch ->
                grid[r][c0 + k] = ch
                ink[r][c0 + k] = true
            }
        }

        // ── pass 2: text, smallest boxes first — they are the ones short of room.
        var inside = 0
        var total = 0
        val onGrid = HashSet<Int>()
        for (gz in geo.sortedBy { (it.c2 - it.c1) * (it.r2 - it.r1) }) {
            val lead = gz.group.first()
            if (!targetable(lead)) continue
            total++
            val tag = if (gz.group.size > 1) "${lead.somId}+${gz.group.size - 1}" else "${lead.somId}"
            val want = if (lead.label.isNotBlank()) "$tag ${lead.label}" else tag

            val ic1 = gz.c1 + 1
            val ic2 = gz.c2 - 1
            val ir1 = gz.r1 + 1
            val ir2 = gz.r2 - 1
            val iw = ic2 - ic1 + 1
            val ih = ir2 - ir1 + 1

            // Search the WHOLE interior, not just its top-left corner. A parent box's
            // top-left is usually already taken by a nested child, but the strip beside
            // or below that child is free — that plateaued the fit rate at 60% until the
            // search learned to look there. Several wrap widths are tried because a
            // narrower, taller block often fits where a wide flat one cannot.
            var placedInside = false
            if (iw > 0 && ih > 0) {
                val widths = sortedSetOf<Int>(reverseOrder(), min(iw, want.length), iw, max(6, iw / 2), max(6, iw / 3))
                for (w in widths) {
                    val lines = wrapWords(want, w)
                    if (lines.isEmpty() || lines.size > ih) continue
                    val span = lines.maxOf { it.length }
                    for (r0 in ir1..(ir2 - lines.size + 1)) {
                        for (c0 in ic1..(ic2 - span + 1)) {
                            if (lines.withIndex().all { (i, ln) -> writable(r0 + i, c0, ln.length) }) {
                                lines.forEachIndexed { i, ln -> emit(r0 + i, c0, ln) }
                                placedInside = true
                                break
                            }
                        }
                        if (placedInside) break
                    }
                    if (placedInside) break
                }
            }
            if (placedInside) {
                inside++
                if (lead.label.isNotBlank()) onGrid += lead.somId
                continue
            }

            // Rule 3 — borrow empty space, still never touching a wall.
            val flat = wrapWords(want, max(8, cols / 4))
            val need = flat.firstOrNull() ?: tag
            var placed = false
            val rowOrder = (gz.r1 until min(gz.r2 + 2, gridRows)) + (max(0, gz.r1 - 1) until gz.r1)
            for (r in rowOrder) {
                for (c in max(0, gz.c1)..min(cols - need.length, gz.c2 + 1)) {
                    if (writable(r, c, need.length)) {
                        emit(r, c, need)
                        placed = true
                        break
                    }
                }
                if (placed) break
            }
            // Whether parked or undrawn, the label is NOT reliably inside its own box,
            // so the table must still carry its text.
        }

        // Trim trailing blank rows: empty space below the last content is real on the
        // phone but says nothing here, and it is the cheapest thing to cut.
        val last = grid.indexOfLast { String(it).isNotBlank() }.coerceAtLeast(0)
        // ASCII borders, not Unicode box-drawing: the U+2500 block isn't guaranteed the
        // same advance width as a plain character in every monospace font, and Android's
        // font-fallback chain can route it through a DIFFERENT font entirely than the rest
        // of the line — measured on-device as staggered, misaligned borders that a desktop
        // terminal's font never showed. Plain '-'/'|'/'+' has no fallback to mismatch.
        val canvas = buildString {
            append("+").append("-".repeat(cols)).append("+")
            for (r in 0..last) append("\n|").append(String(grid[r])).append("|")
            append("\n+").append("-".repeat(cols)).append("+")
        }

        val rows = body.map {
            Row(
                somId = it.somId,
                parent = parent[it.somId],
                flags = it.flags,
                label = it.name,
                labelOnGrid = it.somId in onGrid,
                element = it.src.copy(somId = it.somId),
            )
        }
        val marked = geo.map { it.group.first() }.filter(::targetable).map { it.somId }
        return Rendered(canvas, rows, boxes.size, frames.size, inside, total, cols, last + 1, marked)
    }

    /**
     * Smallest element strictly containing each one — the structure the grid shows
     * visually and a coordinate list cannot state.
     */
    private fun parents(body: List<El>): Map<Int, Int?> {
        val out = HashMap<Int, Int?>(body.size)
        for (e in body) {
            var best: Int? = null
            var bestArea: Int? = null
            for (q in body) {
                if (q === e) continue
                if (q.x1 <= e.x1 && q.y1 <= e.y1 && q.x2 >= e.x2 && q.y2 >= e.y2 &&
                    q.area > e.area && (bestArea == null || q.area < bestArea)
                ) {
                    best = q.somId
                    bestArea = q.area
                }
            }
            out[e.somId] = best
        }
        return out
    }

    /**
     * Map pixel coordinates to character cells, spending cells where content is.
     *
     * A phone is mostly empty space. Mapping pixels linearly spends the grid in
     * proportion to AREA, so a 600px-tall card holding one word eats twelve rows to say
     * "Productivity" while its text is squeezed out. That is the entire reason a naive
     * grid reads as sparse and unusable.
     *
     * Each band between two consecutive box edges is mapped independently:
     *  - a band no label needs gets at most [emptyCap] cells, however tall it is
     *  - a band that must hold text gets at least what that text needs
     *  - whatever budget is left is shared in proportion to real height
     *
     * The mapping stays MONOTONIC, so ordering, nesting and above/below/left-of all
     * survive exactly. What is lost is absolute proportion — a deliberate trade.
     */
    private fun axisMap(
        edges: List<Int>,
        totalPx: Int,
        budget: Int,
        demand: Map<Int, Int>,
        emptyCap: Int,
    ): Map<Int, Int> {
        val n = edges.size - 1
        if (n <= 0) return if (edges.isEmpty()) emptyMap() else mapOf(edges[0] to 0)

        val want = DoubleArray(n) { i ->
            val span = edges[i + 1] - edges[i]
            val natural = span.toDouble() / max(1, totalPx) * budget
            val need = (demand[i] ?: 1).toDouble()
            max(need, min(natural, need + emptyCap))
        }
        val sum = want.sum()
        if (sum > budget) {
            // Over budget: shrink only the slack, never a band's declared minimum.
            val floor = DoubleArray(n) { (demand[it] ?: 1).toDouble() }
            val slack = sum - floor.sum()
            if (slack > 0) {
                val k = max(0.0, 1 - (sum - budget) / slack)
                for (i in 0 until n) want[i] = floor[i] + (want[i] - floor[i]) * k
            }
        }
        val out = HashMap<Int, Int>(edges.size)
        var acc = 0.0
        for (i in 0 until n) {
            out[edges[i]] = acc.roundToInt()
            acc += want[i]
        }
        out[edges[n]] = acc.roundToInt()
        return out
    }

    /** Greedy word wrap. A word longer than the line is split rather than dropped. */
    internal fun wrapWords(text: String, width: Int): List<String> {
        if (width <= 0) return emptyList()
        val out = mutableListOf<String>()
        var line = ""
        for (raw in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var word = raw
            while (word.length > width) {
                if (line.isNotEmpty()) {
                    out += line
                    line = ""
                }
                out += word.take(width)
                word = word.drop(width)
            }
            line = when {
                line.isEmpty() -> word
                line.length + 1 + word.length <= width -> "$line $word"
                else -> {
                    out += line
                    word
                }
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /**
     * One character per actionable property, fixed order so the string is comparable
     * across frames. `-` when none apply, which reads better in a column than a blank.
     */
    internal fun flagsOf(el: DetectedElement): String = buildString {
        if (el.editable) append('e')
        when (el.checked) {
            true -> append('c')
            false -> append('o')
            null -> Unit
        }
        if (!el.enabled) append('d')
        if (el.scrollable) append('S')
        if (el.longClickable) append('l')
        if (el.interactive) append('*')
    }.ifEmpty { "-" }
}
