package com.aura.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Label recovery for the very common Android pattern where the real tap
 * target is a bare row/container (no text of its own) and the informative
 * text belongs to a non-clickable child. There is no parent/child link in
 * the flat element list [UiTreeToElements] parses — bounds containment is
 * the proxy for "belongs to that element".
 */
class UiTreeToElementsTest {

    private fun candidate(l: Int, t: Int, r: Int, b: Int, text: String = "", desc: String = "") =
        LabelCandidate(left = l, top = t, right = r, bottom = b, text = text, contentDescription = desc)

    @Test
    fun `borrows text from a contained descendant when target has none`() {
        val target = candidate(0, 363, 1240, 538)
        val candidates = listOf(
            candidate(112, 411, 939, 496, text = "Wi-Fi"), // the label TextView
            candidate(995, 412, 1128, 496), // the Switch — no text of its own
        )
        assertEquals("Wi-Fi", borrowedLabel(target, candidates))
    }

    @Test
    fun `a row with several texts is named by all of them, not the first`() {
        // F15, the Maps run: the right suggestion row read just "Salem", like the search box and
        // 70 other elements, while the wrong row had the unique name "Salem New Bus Stand".
        val row = candidate(0, 700, 1240, 900)
        val candidates = listOf(
            candidate(40, 720, 600, 790, text = "Salem"),
            candidate(40, 800, 600, 870, text = "Tamil Nadu"),
            candidate(40, 720, 600, 790, text = "Salem"), // a duplicate node does not repeat
        )
        assertEquals("Salem · Tamil Nadu", borrowedLabel(row, candidates))
    }

    @Test
    fun `a borrowed name is capped at three texts`() {
        val row = candidate(0, 0, 1000, 1000)
        val candidates = (1..5).map { candidate(10, it * 100, 900, it * 100 + 50, text = "t$it") }
        assertEquals("t1 · t2 · t3", borrowedLabel(row, candidates))
    }

    @Test
    fun `falls back to contentDescription when no candidate has text`() {
        val target = candidate(0, 0, 200, 100)
        val candidates = listOf(candidate(10, 10, 190, 90, desc = "Wi-Fi details"))
        assertEquals("Wi-Fi details", borrowedLabel(target, candidates))
    }

    @Test
    fun `prefers text over contentDescription among contained candidates`() {
        val target = candidate(0, 0, 200, 100)
        val candidates = listOf(
            candidate(10, 10, 90, 90, desc = "icon description"),
            candidate(100, 10, 190, 90, text = "Save"),
        )
        assertEquals("Save", borrowedLabel(target, candidates))
    }

    @Test
    fun `ignores a candidate that is not contained within the target bounds`() {
        val target = candidate(0, 0, 100, 100)
        // Overlaps but pokes outside the target — not a true descendant.
        val candidates = listOf(candidate(50, 50, 150, 150, text = "Elsewhere"))
        assertEquals("", borrowedLabel(target, candidates))
    }

    @Test
    fun `returns empty when nothing qualifies rather than guessing`() {
        val target = candidate(0, 0, 100, 100)
        val candidates = listOf(candidate(10, 10, 90, 90)) // contained but also blank
        assertEquals("", borrowedLabel(target, candidates))
    }

    @Test
    fun `end-to-end - Wi-Fi toggle row regression from a real device dump`() {
        // Reproduces Settings > Wi-Fi (Oplus/ColorOS): the clickable row that
        // actually toggles Wi-Fi carries no text of its own; "Wi-Fi" belongs to
        // a non-clickable sibling TextView, and the Switch is clickable=false
        // but checkable=true — so it DOES earn its own som_id (see
        // [UiTreeSelectionTest]). Before that change this test asserted the
        // Switch was dropped, which pinned the bug: on split-target rows the
        // agent could then never reach the toggle at all.
        val payloadJson = """
            {
              "elements": [
                {
                  "text": "", "contentDescription": "", "className": "android.widget.LinearLayout",
                  "bounds": {"left":0,"top":363,"right":1240,"bottom":538},
                  "isClickable": true, "isScrollable": false, "isEditable": false,
                  "isEnabled": true, "isFocused": false, "actions": [], "viewId": ""
                },
                {
                  "text": "Wi-Fi", "contentDescription": "", "className": "android.widget.TextView",
                  "bounds": {"left":112,"top":411,"right":939,"bottom":496},
                  "isClickable": false, "isScrollable": false, "isEditable": false,
                  "isEnabled": true, "isFocused": false, "actions": [], "viewId": ""
                },
                {
                  "text": "On", "contentDescription": "", "className": "android.widget.Switch",
                  "bounds": {"left":995,"top":412,"right":1128,"bottom":496},
                  "isClickable": false, "isScrollable": false, "isEditable": false,
                  "isCheckable": true, "isChecked": true,
                  "isEnabled": true, "isFocused": false, "actions": [], "viewId": ""
                },
                {
                  "text": "Saved networks", "contentDescription": "", "className": "android.widget.TextView",
                  "bounds": {"left":80,"top":561,"right":330,"bottom":611},
                  "isClickable": true, "isScrollable": false, "isEditable": false,
                  "isEnabled": true, "isFocused": false, "actions": [], "viewId": ""
                }
              ]
            }
        """.trimIndent()

        val elements = UiTreeToElements.extract(payloadJson, minElements = 1)
        val toggleRow = elements.firstOrNull { it.bbox.x1 == 0 && it.bbox.y1 == 363 }
        assertTrue(toggleRow != null, "the clickable toggle row must survive the interactivity filter")
        // Its texts in order: the name, then the switch's own state text (F15: all of them, not the first).
        assertEquals("Wi-Fi · On", toggleRow!!.label, "must borrow 'Wi-Fi' from the contained TextView, not ship a blank label")

        // Already-labeled elements are untouched — their own label always wins.
        val savedNetworks = elements.first { it.label == "Saved networks" }
        assertEquals("Saved networks", savedNetworks.label)

        // The Switch survives on its own (checkable) and carries its state, so
        // the agent can both aim at it and know it is currently ON.
        val switch = elements.first { it.bbox.x1 == 995 }
        assertEquals(true, switch.checked)

        // CONTRACT CHANGE (2026-08-06): the bare "Wi-Fi" TextView is now KEPT, marked
        // passive, rather than dropped. Label-borrowing above is unaffected — the
        // toggle row still borrows "Wi-Fi" — but the label itself is no longer withheld
        // from the model just because it is not a tap target. Grey boxes say "no action
        // flags"; that is cheaper and more honest than silence, and on real screens the
        // flag is often missing from things that ARE tappable.
        assertEquals(4, elements.size, "every node with a real rect is emitted")
        val bareLabel = elements.first { !it.interactive }
        assertEquals("Wi-Fi", bareLabel.label, "the static label is kept, marked passive")
    }
}
