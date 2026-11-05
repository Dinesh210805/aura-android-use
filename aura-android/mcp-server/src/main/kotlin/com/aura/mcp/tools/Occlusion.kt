package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox

/**
 * Which part of each element a touch can still reach, given what is painted on top of it.
 *
 * Paint order comes from the tree payload: `z` is a node's position in a pre-order walk of its
 * window in drawing order, offset by the window's layer, and `zEnd` is the last `z` inside its
 * subtree. A larger `z` is painted later, so it is on top.
 *
 * - Contract: [resolve] returns one [Verdict] per input, in input order. A node with no paint
 *   order (older payloads, vision detections) gets its plain centre and is never covered.
 * - Why only touch-catching nodes cover: Android passes a touch through views that are not
 *   clickable, scrollable or editable, so a label drawn over a button does not block it. The
 *   exception is a window: its root covers everything in lower windows (a dialog, a keyboard).
 * - Why a node's own descendants never cover it: a touch on a child inside a row lands in that
 *   row's own area (a row and its clickable wrapper share bounds), and counting it would refuse
 *   the targets `read_screen` tells the model to use (`N+k`). Only later-painted nodes OUTSIDE
 *   the subtree cover: a floating button, a sticky bar, a sheet, a dialog window.
 * - Ceiling: a modal dialog also blocks touches outside its own bounds; the tree does not say
 *   which windows are modal, so only the dialog's bounds count.
 */
internal object Occlusion {

    /** One element as occlusion sees it. */
    data class Node(
        val box: BBox,
        val z: Long?,
        val zEnd: Long?,
        val catchesTouch: Boolean,
    )

    /**
     * [tap] is where to touch the node; [coveredBy] is the index of the node that covers it
     * completely, or null when a usable part is visible. When covered, [tap] is the centre and
     * must not be used.
     */
    data class Verdict(val tap: Pair<Int, Int>, val coveredBy: Int?)

    /**
     * Paint-order values per window: a window's root is `layer * WINDOW_STRIDE`.
     *
     * - Change together: `UITreeExtractor.PAINT_WINDOW_STRIDE` in `:app`.
     */
    const val WINDOW_STRIDE = 1_000_000L

    /** The narrowest visible strip still worth aiming at, in pixels (about 5 dp on a phone). */
    const val MIN_TOUCH_PX = 12

    /** Occluders considered per node; past this the grid search costs more than it can buy. */
    private const val MAX_OCCLUDERS = 48

    fun resolve(nodes: List<Node>): List<Verdict> = nodes.map { a -> verdictFor(a, nodes) }

    private fun verdictFor(a: Node, nodes: List<Node>): Verdict {
        val centre = a.box.centerX to a.box.centerY
        if (a.z == null) return Verdict(centre, null)
        val over = nodes.withIndex().filter { (_, b) -> covers(b, a) }
        if (over.isEmpty()) return Verdict(centre, null)
        val clipped = over.mapNotNull { (i, b) -> clip(b.box, a.box)?.let { i to it } }
            .sortedByDescending { (_, r) -> r.area }
            .take(MAX_OCCLUDERS)
        if (clipped.none { (_, r) -> r.containsPoint(centre.first, centre.second) }) {
            return Verdict(centre, null)
        }
        val free = largestFreeRect(a.box, clipped.map { it.second })
        if (free != null && free.width >= MIN_TOUCH_PX && free.height >= MIN_TOUCH_PX) {
            return Verdict(free.centerX to free.centerY, null)
        }
        // Name the topmost node over the centre: that is what a touch there would reach.
        val top = over.filter { (_, b) -> b.box.containsPoint(centre.first, centre.second) }
            .maxByOrNull { (_, b) -> b.z ?: Long.MIN_VALUE }
            ?: over.maxBy { (_, b) -> clip(b.box, a.box)?.area ?: 0 }
        return Verdict(centre, top.index)
    }

    /** Whether [b] is painted over [a], catches touches, overlaps it and is not inside it. */
    private fun covers(b: Node, a: Node): Boolean {
        val bz = b.z ?: return false
        val az = a.z ?: return false
        // A window's root takes every touch inside its bounds, clickable or not: Android picks
        // the topmost window under the finger first, and never hands the touch down.
        val windowOver = bz % WINDOW_STRIDE == 0L && bz / WINDOW_STRIDE > az / WINDOW_STRIDE
        if (!(b.catchesTouch || windowOver) || bz <= az) return false
        val aEnd = a.zEnd ?: az
        if (bz <= aEnd) return false
        return clip(b.box, a.box) != null
    }

    private fun clip(r: BBox, to: BBox): BBox? {
        val x1 = maxOf(r.x1, to.x1)
        val y1 = maxOf(r.y1, to.y1)
        val x2 = minOf(r.x2, to.x2)
        val y2 = minOf(r.y2, to.y2)
        return if (x2 > x1 && y2 > y1) BBox(x1, y1, x2, y2) else null
    }

    /** Half-open, matching how Android hit-tests a view's bounds. */
    private fun BBox.containsPoint(x: Int, y: Int): Boolean = x in x1 until x2 && y in y1 until y2

    /**
     * The largest axis-aligned rectangle inside [box] that no rectangle in [blocks] touches.
     *
     * Splits [box] into a grid on every block edge, so each cell is either fully covered or
     * fully free, then finds the largest run of free cells by pixel area. O(cols² · rows) on at
     * most [MAX_OCCLUDERS] blocks.
     */
    private fun largestFreeRect(box: BBox, blocks: List<BBox>): BBox? {
        val xs = (listOf(box.x1, box.x2) + blocks.flatMap { listOf(it.x1, it.x2) }).distinct().sorted()
        val ys = (listOf(box.y1, box.y2) + blocks.flatMap { listOf(it.y1, it.y2) }).distinct().sorted()
        val cols = xs.size - 1
        val rows = ys.size - 1
        val free = Array(rows) { r ->
            BooleanArray(cols) { c ->
                val cx = (xs[c] + xs[c + 1]) / 2
                val cy = (ys[r] + ys[r + 1]) / 2
                blocks.none { it.containsPoint(cx, cy) }
            }
        }
        var best: BBox? = null
        for (c1 in 0 until cols) {
            for (c2 in c1 until cols) {
                var runStart = -1
                for (r in 0..rows) {
                    val open = r < rows && (c1..c2).all { free[r][it] }
                    if (open && runStart < 0) runStart = r
                    if (!open && runStart >= 0) {
                        val cand = BBox(xs[c1], ys[runStart], xs[c2 + 1], ys[r])
                        if (best == null || cand.area > best.area) best = cand
                        runStart = -1
                    }
                }
            }
        }
        return best
    }
}

/**
 * Which node a touch at a point reaches when AURA delivers it as an accessibility click.
 *
 * Android gives a touch to the topmost view under it that handles touches; a click sent through
 * accessibility skips that routing, so the fallback has to pick the same node itself.
 *
 * - Contract: [candidates] are in paint order, bottom first (windows by ascending layer, each
 *   walked pre-order in drawing order). Returns the index of the topmost enabled clickable
 *   candidate containing the point, else the topmost enabled one, else null. AURA's own windows
 *   ([Candidate.own]) are never picked: its pause button must not receive the agent's taps.
 * - Change together: `SemanticClicker.clickableNodeAt` in `:app`, which builds the list.
 */
object TouchTarget {

    data class Candidate(
        val box: BBox,
        val clickable: Boolean,
        val enabled: Boolean,
        val own: Boolean = false,
    )

    fun pick(candidates: List<Candidate>, x: Int, y: Int): Int? {
        var clickable: Int? = null
        var any: Int? = null
        candidates.forEachIndexed { i, c ->
            if (c.own || !c.enabled) return@forEachIndexed
            if (x !in c.box.x1 until c.box.x2 || y !in c.box.y1 until c.box.y2) return@forEachIndexed
            any = i
            if (c.clickable) clickable = i
        }
        return clickable ?: any
    }
}
