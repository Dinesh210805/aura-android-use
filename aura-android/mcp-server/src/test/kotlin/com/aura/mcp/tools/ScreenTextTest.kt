package com.aura.mcp.tools

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accessibility tree carries every heading, price, status line and list
 * subtitle on screen. [UiTreeToElements] deliberately keeps only INTERACTIVE
 * nodes — that is correct for som_id targets, but it means the content itself
 * never reached the model, and OmniParser's OCR was re-deriving from pixels
 * what the framework had already handed us for free.
 *
 * [ScreenText] is the second channel: the text the tree knows about and the
 * element list drops.
 */
class ScreenTextTest {

    private fun node(
        text: String = "",
        desc: String = "",
        l: Int = 0,
        t: Int = 0,
        r: Int = 100,
        b: Int = 50,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
    ) = """
        {
          "text": ${quote(text)},
          "contentDescription": ${quote(desc)},
          "className": "android.widget.TextView",
          "isClickable": $clickable,
          "isEditable": $editable,
          "isScrollable": $scrollable,
          "isCheckable": false,
          "isLongClickable": false,
          "isEnabled": true,
          "actions": [],
          "bounds": { "left": $l, "top": $t, "right": $r, "bottom": $b }
        }
    """.trimIndent()

    private fun quote(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    private fun payload(vararg nodes: String, validationFailed: Boolean = false) = """
        { "validation_failed": $validationFailed, "elements": [ ${nodes.joinToString(",")} ] }
    """.trimIndent()

    @Test
    fun `returns the non-interactive text the element list drops`() {
        val json = payload(
            node(text = "Order total", t = 100, b = 150),
            node(text = "₹450", t = 160, b = 210),
        )
        assertEquals(listOf("Order total", "₹450"), ScreenText.extract(json).map { it.text })
    }

    @Test
    fun `skips interactive nodes because they already ship as elements`() {
        val json = payload(
            node(text = "Heading", t = 0, b = 50),
            node(text = "Place order", t = 60, b = 110, clickable = true),
            node(text = "Search here", t = 120, b = 170, editable = true),
        )
        assertEquals(listOf("Heading"), ScreenText.extract(json).map { it.text })
    }

    @Test
    fun `falls back to contentDescription when a node has no text`() {
        val json = payload(node(desc = "Profile photo of Dinesh"))
        assertEquals(listOf("Profile photo of Dinesh"), ScreenText.extract(json).map { it.text })
    }

    @Test
    fun `drops blank nodes and degenerate bounds`() {
        val json = payload(
            node(text = "   ", t = 0, b = 50),
            node(text = "Zero height", t = 60, b = 60),
            node(text = "Kept", t = 70, b = 120),
        )
        assertEquals(listOf("Kept"), ScreenText.extract(json).map { it.text })
    }

    @Test
    fun `drops text already borrowed as a containing clickable's label`() {
        // The Wi-Fi settings row: clickable container with no text of its own,
        // "Wi-Fi" on a non-clickable child. UiTreeToElements borrows it as the
        // row's label, so repeating it here is pure duplication.
        val json = payload(
            node(l = 0, t = 363, r = 1240, b = 538, clickable = true),
            node(text = "Wi-Fi", l = 112, t = 411, r = 939, b = 496),
            node(text = "Connected to AURA-5G", l = 112, t = 500, r = 939, b = 530),
        )
        // The subtitle is borrowed too since F15 (the row is named "Wi-Fi · Connected to
        // AURA-5G"), so it is not lost — it moved into the label, and repeating it here
        // would be the same duplication.
        assertEquals(emptyList<String>(), ScreenText.extract(json).map { it.text })
    }

    @Test
    fun `sorts top to bottom then left to right`() {
        val json = payload(
            node(text = "second row", l = 0, t = 200, r = 100, b = 250),
            node(text = "first row right", l = 500, t = 100, r = 600, b = 150),
            node(text = "first row left", l = 0, t = 100, r = 100, b = 150),
        )
        assertEquals(
            listOf("first row left", "first row right", "second row"),
            ScreenText.extract(json).map { it.text },
        )
    }

    @Test
    fun `deduplicates identical text at identical bounds`() {
        val json = payload(
            node(text = "Inbox", l = 0, t = 0, r = 100, b = 50),
            node(text = "Inbox", l = 0, t = 0, r = 100, b = 50),
        )
        assertEquals(1, ScreenText.extract(json).size)
    }

    @Test
    fun `returns empty for a validation-failed tree`() {
        val json = payload(node(text = "Anything"), validationFailed = true)
        assertTrue(ScreenText.extract(json).isEmpty())
    }

    @Test
    fun `returns empty for malformed json rather than throwing`() {
        assertTrue(ScreenText.extract("not json at all").isEmpty())
    }

    @Test
    fun `caps the number of blocks returned`() {
        val nodes = (0 until 50).map { node(text = "row $it", t = it * 10, b = it * 10 + 8) }
        assertEquals(10, ScreenText.extract(payload(*nodes.toTypedArray()), limit = 10).size)
    }

    @Test
    fun `serializes to the payload shape the model reads`() {
        val json = payload(node(text = "Order total", l = 12, t = 34, r = 56, b = 78))
        val array = ScreenText.toJson(ScreenText.extract(json))
        assertEquals(1, array.size)
        val obj = array.single().jsonObject
        assertEquals("Order total", obj["text"]?.jsonPrimitive?.content)
        assertEquals(34, obj["center_x"]?.jsonPrimitive?.int)
        assertEquals(56, obj["center_y"]?.jsonPrimitive?.int)
    }

    @Test
    fun `keeps bounds so the model can ground text against elements`() {
        val json = payload(node(text = "Total", l = 12, t = 34, r = 56, b = 78))
        val block = ScreenText.extract(json).single()
        assertEquals(12, block.bbox.x1)
        assertEquals(34, block.bbox.y1)
        assertEquals(56, block.bbox.x2)
        assertEquals(78, block.bbox.y2)
    }

    // ── off-screen channel ───────────────────────────────────────────────────

    @Test
    fun `offscreen text is read back as bare strings`() {
        // The words are real; the position is not. Android reports a node's rect
        // clipped to what is VISIBLE, so a carousel card scrolled off to the right
        // arrives as a zero-area sliver at the viewport edge. The walk keeps what it
        // said and discards where it claimed to be.
        val payload = """
            {"elements":[],"offscreen_text":["Starting Rs 31,999*","8000mAh battery","Up to 45% off"]}
        """.trimIndent()

        val offscreen = ScreenText.offscreen(payload)

        assertEquals(listOf("Starting Rs 31,999*", "8000mAh battery", "Up to 45% off"), offscreen)
    }

    @Test
    fun `a payload with no offscreen_text yields an empty list, not an error`() {
        assertEquals(emptyList(), ScreenText.offscreen("""{"elements":[]}"""))
        assertEquals(emptyList(), ScreenText.offscreen("not json at all"))
    }

    @Test
    fun `blank offscreen entries are dropped`() {
        val payload = """{"offscreen_text":["  ","Real offer","	"]}"""

        assertEquals(listOf("Real offer"), ScreenText.offscreen(payload))
    }
}
