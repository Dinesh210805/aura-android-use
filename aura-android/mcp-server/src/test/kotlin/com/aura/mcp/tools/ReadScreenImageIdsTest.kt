package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `read_screen`'s annotated image and its character grid must number the same element the
 * same way. A mismatch does not error — the model reads "7" off the picture, taps 7, and the
 * cache resolves a different element — so the invariant is pinned here, off-device.
 */
class ReadScreenImageIdsTest {

    private fun el(x1: Int, y1: Int, x2: Int, y2: Int, label: String = "", interactive: Boolean = true) =
        DetectedElement(
            somId = 0,
            bbox = BBox(x1, y1, x2, y2),
            elementType = "Button",
            label = label,
            confidence = 1f,
            interactive = interactive,
        )

    private val screen = listOf(
        el(0, 0, 1080, 2400, interactive = false), // full-screen frame: never numbered
        el(0, 200, 1080, 400, label = "Search"),
        el(0, 500, 540, 700, label = "Home"),
        el(540, 500, 1080, 700, label = "Cart"),
        // A row and its clickable wrapper sharing exact bounds → one box, "N+1" on the grid.
        el(0, 900, 1080, 1100, label = "Settings"),
        el(0, 900, 1080, 1100),
        el(0, 1200, 1080, 1400, interactive = false), // unlabelled passive wrapper
    )

    private val render = ScreenPayload.render(screen, pkg = "com.x", idle = true, settled = true)!!

    @Test
    fun `every image id is an id the grid assigned to that exact element`() {
        val byId = render.elements.associateBy { it.somId }
        for (m in render.marked) {
            assertEquals(byId[m.somId], m, "image element ${m.somId} is not the grid's element ${m.somId}")
        }
    }

    @Test
    fun `every number written on the grid is drawn on the image`() {
        // One direction only: a tag with no room may be left off the grid, but the box
        // still carries its number on the image.
        val onGrid = Regex("""(?<![\w+])(\d+)(?:\+\d+)?(?=[ |]|$)""", RegexOption.MULTILINE)
            .findAll(render.text.substringAfter("labels are drawn on the grid").substringBefore("som  in"))
            .map { it.groupValues[1].toInt() }
            .toSet()
        assertTrue(onGrid.isNotEmpty())
        assertTrue(render.marked.map { it.somId }.containsAll(onGrid), "grid $onGrid vs image ${render.marked.map { it.somId }}")
    }

    @Test
    fun `shared bounds draw one box numbered with the grid's lead id`() {
        val settings = render.marked.filter { it.bbox == BBox(0, 900, 1080, 1100) }
        assertEquals(1, settings.size)
        assertTrue(render.text.contains("${settings.single().somId}+1"))
    }
}
