package com.aura.aura_ui.mcp.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace's text-SoM: an annotated screenshot with no pixels.
 *
 * A trace step currently shows `[[0,0,1240,120,"Back","*","Button"], …]`, from which no
 * human can picture the screen. This draws it — boxes on a character grid with the
 * agent's own som_ids inside them — so a person reading the trace sees what the agent
 * was choosing between.
 *
 * Pure (JSON in, string out), so no Robolectric runner is needed. It uses
 * kotlinx.serialization rather than `org.json` for exactly that reason: `org.json` is
 * stubbed on the JVM and throws "not mocked" in `:app` unit tests.
 */
class ScreenCanvasTest {

    /** A `read_screen` frame: elements are [x1,y1,x2,y2,label,flags,class]. */
    private fun readScreenPayload(vararg rows: String) =
        """{"v":1,"pkg":"com.example","w":1000,"h":2000,"n":${rows.size},""" +
            """"ui_tree_count":${rows.size},"idle":true,"settled":true,"e":[${rows.joinToString(",")}]}"""

    private fun box(x1: Int, y1: Int, x2: Int, y2: Int, label: String, flags: String = "*") =
        """[$x1,$y1,$x2,$y2,"$label","$flags","Button"]"""

    private fun assertHas(haystack: String, needle: String) =
        assertTrue("expected \"$needle\" in:\n$haystack", haystack.contains(needle))

    @Test
    fun `renders a bordered canvas with the screen summary above it`() {
        val out = ScreenCanvas.render(readScreenPayload(box(0, 0, 500, 200, "Back")))!!

        assertHas(out, "com.example")
        assertHas(out, "1000x2000")
        val borderLines = out.lines().filter(::isBorderLine)
        assertTrue("canvas needs a top and bottom border", borderLines.size >= 2)
    }

    /**
     * The single most important property. `livescreen.py` re-sorts elements by area and
     * renumbers them 1..n for its own display — doing that here would draw a picture
     * whose numbers do not match the som_ids the agent actually received, which is worse
     * than drawing nothing: someone debugging a mis-tap would chase the wrong element.
     */
    @Test
    fun `som_ids are the agent's own numbering, never renumbered by area`() {
        // Deliberately smallest-first, the opposite of livescreen.py's draw order.
        val out = ScreenCanvas.render(
            readScreenPayload(
                box(0, 0, 100, 100, "tiny"),
                box(0, 200, 900, 1900, "huge"),
            ),
        )!!

        val tinyLine = out.lines().first { it.contains("tiny") }
        val hugeLine = out.lines().first { it.contains("huge") }
        assertTrue("first element in e must be som_id 1", tinyLine.trimStart().startsWith("1"))
        assertTrue("second element in e must be som_id 2", hugeLine.trimStart().startsWith("2"))
    }

    @Test
    fun `labels are listed below the canvas with their flags`() {
        val out = ScreenCanvas.render(
            readScreenPayload(
                box(0, 0, 500, 200, "Search", flags = "e"),
                box(0, 300, 500, 500, "Add to cart", flags = "*"),
            ),
        )!!

        assertHas(out, "Search")
        assertHas(out, "Add to cart")
        val searchRow = out.lines().first { it.contains("Search") }
        assertHas(searchRow, "e")
    }

    /**
     * Android UI trees produce these constantly — a row and its clickable wrapper share
     * bounds exactly. Drawing them on top of each other would hide one number under
     * another; `1+1` says "som_id 1, plus one more element in the same box".
     */
    @Test
    fun `elements sharing identical bounds collapse to one box tagged N+k`() {
        val out = ScreenCanvas.render(
            readScreenPayload(
                box(0, 0, 800, 400, "Chicken Biryani"),
                box(0, 0, 800, 400, ""),
            ),
        )!!

        assertHas(out, "1+1")
    }

    /**
     * `perceive_screen`'s `e` is [cx, cy, name, flags] — centre points, no bounds. There
     * is no box to draw, so the number is plotted at its point. Rendering it with
     * read_screen's offsets would read x/y as a bounding box and draw nonsense.
     */
    @Test
    fun `perceive_screen centre-point payloads plot points instead of boxes`() {
        val payload = """{"e":[[500,1000,"Play","*"],[250,300,"Back","*"]],"ui_tree_count":2}"""
        val out = ScreenCanvas.render(payload)!!

        assertHas(out, "Play")
        assertHas(out, "Back")
        assertTrue("still draws a canvas", out.lines().any { it.startsWith("+") })
    }

    /**
     * perceive_screen returns SEVERAL content blocks joined with newlines — prose first,
     * JSON last. ActionGuard parsed the whole string for weeks, threw every time, and
     * silently fell back to a broken hash. Any new reader must take the last JSON line.
     */
    @Test
    fun `finds the payload when prose precedes the JSON`() {
        val payload = "Perceived screen: 'the search bar'\nperception_tier=tree_only\n" +
            readScreenPayload(box(0, 0, 500, 200, "Back"))

        val out = ScreenCanvas.render(payload)
        assertTrue("must parse the last JSON line", out != null && out.contains("Back"))
    }

    @Test
    fun `returns null rather than a misleading empty canvas when there is nothing to draw`() {
        assertNull(ScreenCanvas.render("not json at all"))
        assertNull(ScreenCanvas.render("""{"e":[]}"""))
        assertNull(ScreenCanvas.render("""{"pkg":"com.example"}"""))
    }

    /** Degenerate boxes are dropped everywhere else in the pipeline; drawing is no different. */
    @Test
    fun `degenerate boxes do not consume a som_id slot in the drawing`() {
        val out = ScreenCanvas.render(
            readScreenPayload(
                box(0, 0, 500, 200, "real"),
                box(300, 300, 300, 300, "degenerate"),
                box(0, 400, 500, 600, "alsoreal"),
            ),
        )!!

        // som_id 3 must still be 3 — dropping element 2 from the DRAWING must not
        // renumber element 3, or the picture disagrees with the agent's numbering.
        val line = out.lines().first { it.contains("alsoreal") }
        assertTrue("expected som_id 3, got: $line", line.trimStart().startsWith("3"))
    }

    @Test
    fun `canvas respects the requested width so it fits a phone trace view`() {
        val out = ScreenCanvas.render(readScreenPayload(box(0, 0, 500, 200, "Back")), cols = 20)!!
        val border = out.lines().first { it.startsWith("+") }
        assertEquals("border is cols + 2 corner chars", 22, border.length)
    }

    @Test
    fun `every canvas row is exactly the canvas width so the box never looks torn`() {
        val out = ScreenCanvas.render(
            readScreenPayload(box(0, 0, 900, 1900, "big"), box(100, 100, 400, 500, "small")),
            cols = 30,
        )!!
        val rows = out.lines().filter { it.startsWith("|") }
        assertTrue(rows.isNotEmpty())
        rows.forEach { assertEquals("ragged row: '$it'", 32, it.length) }
    }
}

/** A whole border row (`+---+`), not just any line that happens to contain a `+` — the
 * `N+k` legend text does too, and `startsWith` alone would match it on the wrong line. */
private fun isBorderLine(line: String): Boolean =
    line.isNotEmpty() && line.all { it == '+' || it == '-' }
