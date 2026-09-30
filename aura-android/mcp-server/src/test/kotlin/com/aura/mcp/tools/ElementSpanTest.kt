package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElementSpanTest {

    // A carousel row: 0..1000 wide, 400..600 tall.
    private val row = BBox(0, 400, 1000, 600)

    @Test
    fun `scroll right reveals content to the right, so the finger drags left`() {
        val p = ElementSpan.forScroll(row, ElementSpan.Direction.RIGHT)
        assertTrue(p.x1 > p.x2, "finger must move leftward: $p")
        assertEquals(500, p.y1)
        assertEquals(p.y1, p.y2)
    }

    @Test
    fun `scroll down reveals content below, so the finger drags up`() {
        val p = ElementSpan.forScroll(row, ElementSpan.Direction.DOWN)
        assertTrue(p.y1 > p.y2, "finger must move upward: $p")
    }

    @Test
    fun `swipe left moves the finger left`() {
        val p = ElementSpan.forSwipe(row, ElementSpan.Direction.LEFT)
        assertTrue(p.x1 > p.x2)
    }

    @Test
    fun `the drag stays inside the element`() {
        for (d in ElementSpan.Direction.entries) {
            val p = ElementSpan.forSwipe(row, d)
            listOf(p.x1, p.x2).forEach { assertTrue(it in row.x1..row.x2, "$d x out of bounds: $p") }
            listOf(p.y1, p.y2).forEach { assertTrue(it in row.y1..row.y2, "$d y out of bounds: $p") }
        }
    }

    @Test
    fun `direction parsing is case-insensitive and rejects junk`() {
        assertEquals(ElementSpan.Direction.LEFT, ElementSpan.parse(" Left "))
        assertNull(ElementSpan.parse("sideways"))
        assertNull(ElementSpan.parse(null))
    }
}
