package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox

/**
 * Start and end points for a gesture aimed INSIDE one element, so the model can scroll a
 * carousel or swipe a slider by som_id instead of by raw pixels (the doctrine forbids pixels).
 *
 * The drag covers the middle [SPAN] of the element on the gesture's axis, so it starts and ends
 * inside the element without touching its edges.
 */
internal object ElementSpan {

    data class Points(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    private const val SPAN = 0.6

    /** Parse "up" | "down" | "left" | "right" (case-insensitive); null for anything else. */
    fun parse(raw: String?): Direction? =
        raw?.trim()?.uppercase()?.let { v -> Direction.entries.firstOrNull { it.name == v } }

    /**
     * Viewport convention, matching scroll_* and `CoordinateResolver`: the direction names what
     * you want to SEE next. scroll down reveals content below, so the finger drags UP.
     */
    fun forScroll(box: BBox, direction: Direction): Points = when (direction) {
        Direction.DOWN -> forSwipe(box, Direction.UP)
        Direction.UP -> forSwipe(box, Direction.DOWN)
        Direction.RIGHT -> forSwipe(box, Direction.LEFT)
        Direction.LEFT -> forSwipe(box, Direction.RIGHT)
    }

    /** Finger convention: the direction is where the finger MOVES (swipe left = drag leftward). */
    fun forSwipe(box: BBox, direction: Direction): Points {
        val halfW = (box.width * SPAN / 2).toInt()
        val halfH = (box.height * SPAN / 2).toInt()
        val cx = box.centerX
        val cy = box.centerY
        return when (direction) {
            Direction.UP -> Points(cx, cy + halfH, cx, cy - halfH)
            Direction.DOWN -> Points(cx, cy - halfH, cx, cy + halfH)
            Direction.LEFT -> Points(cx + halfW, cy, cx - halfW, cy)
            Direction.RIGHT -> Points(cx - halfW, cy, cx + halfW, cy)
        }
    }
}
