package com.aura.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selection policy: which accessibility nodes earn a SoM box.
 *
 * The original policy was `isClickable || isScrollable || isEditable`, which
 * relies on a single developer-set boolean. Two failure modes were observed on
 * real device dumps and are pinned here:
 *
 *  1. A `Switch` inside a preference row is `isClickable=false` but
 *     `isCheckable=true`. Under the old policy it never became a target, so on
 *     the split-target layout (`androidx.preference.TwoTargetPreference`, used
 *     by notification channels and Bluetooth device rows) the agent could only
 *     tap the row — which NAVIGATES — and could never toggle. Gestures are
 *     dispatched as a synthetic touch at a coordinate, so marking the widget is
 *     never worse than marking only the row, and is the only thing that works
 *     when the two are separate affordances.
 *  2. A disabled placeholder can carry `isClickable=true` while exposing no
 *     click action at all. It became a normal-looking box the agent would tap
 *     for no effect, silently burning a turn.
 */
class UiTreeSelectionTest {

    private fun node(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        text: String = "",
        className: String = "android.widget.LinearLayout",
        clickable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        longClickable: Boolean = false,
        enabled: Boolean = true,
        actions: String = "",
    ) = """
        {
          "text": "$text", "contentDescription": "", "className": "$className",
          "bounds": {"left":$l,"top":$t,"right":$r,"bottom":$b},
          "isClickable": $clickable, "isScrollable": false, "isEditable": false,
          "isCheckable": $checkable, "isChecked": $checked,
          "isLongClickable": $longClickable,
          "isEnabled": $enabled, "isFocused": false, "actions": [$actions], "viewId": ""
        }
    """.trimIndent()

    private fun payload(vararg nodes: String) = """{"elements": [${nodes.joinToString(",")}]}"""

    @Test
    fun `checkable switch becomes its own target even when isClickable is false`() {
        // Real OPlus Wi-Fi dump: the row owns the click handler, the Switch is
        // inert in the tree but is the correct thing to aim at on split layouts.
        val json = payload(
            node(0, 363, 1240, 538, clickable = true, actions = "\"click\""),
            node(995, 412, 1128, 496, text = "On", className = "android.widget.Switch", checkable = true, checked = true),
        )

        val elements = UiTreeToElements.extract(json, minElements = 1)

        val switch = elements.firstOrNull { it.bbox.x1 == 995 }
        assertTrue(switch != null, "a checkable widget must earn its own som_id regardless of isClickable")
    }

    @Test
    fun `checked state is exposed so the agent knows which way to toggle`() {
        val json = payload(
            node(0, 363, 1240, 538, clickable = true, actions = "\"click\""),
            node(995, 412, 1128, 496, text = "On", className = "android.widget.Switch", checkable = true, checked = true),
        )

        val switch = UiTreeToElements.extract(json, minElements = 1).first { it.bbox.x1 == 995 }

        assertEquals(true, switch.checked, "an ON switch must report checked=true, else the agent turns it off")
    }

    @Test
    fun `non-checkable element reports null checked rather than a misleading false`() {
        val json = payload(node(0, 363, 1240, 538, text = "Refresh", clickable = true, actions = "\"click\""))

        val row = UiTreeToElements.extract(json, minElements = 1).first()

        assertNull(row.checked, "checked must be absent for things that cannot be checked")
    }

    @Test
    fun `disabled node is kept but tagged so the agent can see it is unavailable`() {
        // "No available networks" placeholder: isClickable=true, isEnabled=false,
        // actions=[]. Tapping it does nothing.
        //
        // Dropping it outright was tried and made things WORSE on-device: the
        // OmniParser pass simply OCR'd the same pixels back as a red element,
        // so the agent still saw a target — now with fuzzier bounds and no
        // disabled signal at all. Keeping the tree node both tells the agent
        // it is unavailable AND lets mergeElements swallow the CV duplicate,
        // because the CV box is contained by this one.
        val json = payload(
            node(0, 1501, 1240, 1676, text = "No available networks", clickable = true, enabled = false),
            node(0, 1676, 1240, 1851, text = "Add network", clickable = true, actions = "\"click\""),
        )

        val elements = UiTreeToElements.extract(json, minElements = 1)

        val placeholder = elements.firstOrNull { it.bbox.y1 == 1501 }
        assertTrue(placeholder != null, "a disabled control must stay visible to the agent")
        assertEquals(false, placeholder!!.enabled, "it must be tagged disabled, not silently offered as tappable")
    }

    @Test
    fun `enabled elements report enabled true`() {
        val json = payload(node(0, 1676, 1240, 1851, text = "Add network", clickable = true, actions = "\"click\""))

        assertEquals(true, UiTreeToElements.extract(json, minElements = 1).first().enabled)
    }

    @Test
    fun `node exposing a click action is kept even when isClickable is false`() {
        // Custom views can register a click handler without the flag agreeing.
        val json = payload(node(10, 10, 200, 100, text = "Send", clickable = false, actions = "\"click\""))

        val elements = UiTreeToElements.extract(json, minElements = 1)

        assertEquals(1, elements.size, "capability (actions) must count, not just the declared flag")
    }

    @Test
    fun `long-clickable-only node is kept`() {
        val json = payload(node(10, 10, 200, 100, text = "Message", longClickable = true))

        assertEquals(1, UiTreeToElements.extract(json, minElements = 1).size)
    }

    @Test
    fun `a node with no interactive capability is KEPT, marked passive`() {
        // CONTRACT CHANGE (2026-08-06). This used to assert that non-interactive nodes
        // were dropped, on the reasoning that a filter keeps 58 raw nodes from becoming
        // 58 ambiguous boxes. Measured on a real Amazon home screen, that filter threw
        // away 43 DISTINCT on-screen regions — 40 of them a plausible size for a control
        // — because Android apps routinely omit isClickable on things that are in fact
        // tappable. This project's own TreeSufficiency KDoc records the same finding.
        //
        // Ambiguity is now resolved by BOX COLOUR (grey = no action flags) rather than
        // by refusing to mention the element. That costs no tokens, and it lets the
        // vision model decide by looking instead of trusting a flag the app never set.
        val json = payload(
            node(0, 0, 1240, 2772), // full-screen container
            node(112, 797, 468, 863, text = "Saved networks"), // section header
            node(10, 10, 200, 100, text = "Send", clickable = true, actions = "\"click\""),
        )

        val elements = UiTreeToElements.extract(json, minElements = 1)

        assertEquals(3, elements.size, "every node with a real rect is emitted")
        assertEquals(
            2,
            elements.count { !it.interactive },
            "the container and the static label are marked passive, not made absent",
        )
        assertEquals(
            1,
            elements.count { it.interactive },
            "the genuinely clickable node is still distinguishable — grey box vs blue",
        )
    }

    @Test
    fun `absent isEnabled is treated as enabled for backward compatibility`() {
        // Older payloads (and OmniParser-only paths) omit the field entirely.
        val json = """
            {"elements": [{
              "text": "Send", "contentDescription": "", "className": "android.widget.Button",
              "bounds": {"left":10,"top":10,"right":200,"bottom":100},
              "isClickable": true, "isScrollable": false, "isEditable": false,
              "actions": ["click"], "viewId": ""
            }]}
        """.trimIndent()

        assertEquals(1, UiTreeToElements.extract(json, minElements = 1).size)
    }
}
