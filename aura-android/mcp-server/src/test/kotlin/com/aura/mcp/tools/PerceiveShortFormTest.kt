package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The short-form wire shape, the flag alphabet, and the role→colour map.
 *
 * These three together are the whole contract between the server and the model now:
 * the picture says what a box IS (colour), the array says where to tap and what state
 * it is in, and nothing else is shipped. A field that is neither drawn nor emitted
 * here does not exist as far as the agent is concerned.
 */
class PerceiveShortFormTest {

    private fun el(
        somId: Int = 1,
        label: String = "Join Prime",
        checked: Boolean? = null,
        enabled: Boolean = true,
        interactive: Boolean = true,
        editable: Boolean = false,
        scrollable: Boolean = false,
        longClickable: Boolean = false,
        focused: Boolean = false,
        wrapper: Boolean = false,
        confidence: Float = 1.0f,
    ) = DetectedElement(
        somId = somId,
        bbox = BBox(876, 634, 1191, 743),
        elementType = "Button",
        label = label,
        confidence = confidence,
        checked = checked,
        enabled = enabled,
        interactive = interactive,
        editable = editable,
        scrollable = scrollable,
        longClickable = longClickable,
        focused = focused,
        wrapper = wrapper,
    )

    // ── shape ────────────────────────────────────────────────────────────────

    @Test
    fun `a plain element is center then name`() {
        val a = detectedElementShortJson(el())

        assertEquals(3, a.size)
        assertEquals(1033, a[0].jsonPrimitive.intOrNull) // (876+1191)/2
        assertEquals(688, a[1].jsonPrimitive.intOrNull) // (634+743)/2
        assertEquals("Join Prime", a[2].jsonPrimitive.contentOrNull)
    }

    @Test
    fun `an unnamed element with no flags is just a coordinate pair`() {
        val a = detectedElementShortJson(el(label = ""))

        assertEquals(2, a.size, "trailing empties are omitted, not sent as empty strings")
    }

    @Test
    fun `flags occupy the fourth slot`() {
        val a = detectedElementShortJson(el(editable = true))

        assertEquals(4, a.size)
        assertEquals("e", a[3].jsonPrimitive.contentOrNull)
    }

    @Test
    fun `an unnamed element WITH flags still emits the empty name`() {
        // Position is the whole contract — flags must never slide into the name slot.
        val a = detectedElementShortJson(el(label = "", editable = true))

        assertEquals(4, a.size)
        assertEquals("", a[2].jsonPrimitive.contentOrNull)
        assertEquals("e", a[3].jsonPrimitive.contentOrNull)
    }

    // ── flags ────────────────────────────────────────────────────────────────

    @Test
    fun `no flags on an ordinary enabled button`() {
        assertEquals("", elementFlags(el()))
    }

    @Test
    fun `checked and unchecked are distinct, and absent means no on-off state`() {
        // "off" and "has no on/off state" must never collapse into one another —
        // conflating them is how an agent turns Wi-Fi off while trying to turn it on.
        assertEquals("c", elementFlags(el(checked = true)))
        assertEquals("o", elementFlags(el(checked = false)))
        assertEquals("", elementFlags(el(checked = null)))
    }

    @Test
    fun `editable is flagged even though the box is already green`() {
        // Deliberate redundancy: mis-tapping a text field fails SILENTLY, so it gets
        // both a colour and a character. See elementFlags' KDoc.
        assertTrue(elementFlags(el(editable = true)).contains('e'))
    }

    @Test
    fun `scrollable is NOT flagged - colour alone carries it`() {
        // Picking a scroll target by mistake produces a visible no-op, which the agent
        // notices and can recover from. Only silent failures earn a character.
        assertEquals("", elementFlags(el(scrollable = true)))
    }

    @Test
    fun `disabled, wrapper, focused and long-press each get a character`() {
        assertEquals("d", elementFlags(el(enabled = false)))
        assertEquals("w", elementFlags(el(wrapper = true)))
        assertEquals("f", elementFlags(el(focused = true)))
        assertEquals("l", elementFlags(el(longClickable = true)))
    }

    @Test
    fun `a low-confidence vision box is marked with a question mark`() {
        assertTrue(elementFlags(el(confidence = 0.3f)).contains('?'))
        assertFalse(elementFlags(el(confidence = 1.0f)).contains('?'))
    }

    @Test
    fun `flags combine in a stable order`() {
        val f = elementFlags(el(editable = true, checked = false, enabled = false, focused = true))
        assertEquals("eodf", f)
    }

    // ── colour ───────────────────────────────────────────────────────────────

    @Test
    fun `vision boxes keep their own colour whatever their role`() {
        // "Is this a fact or a guess" outranks "what kind of thing is it".
        val guess = roleColor(MergedElement(el(editable = true), "omniparser"))
        val fact = roleColor(MergedElement(el(editable = true), "ui_tree"))

        assertTrue(guess != fact)
    }

    @Test
    fun `editable outranks tappable`() {
        // An editable field is usually clickable too, and typing is what the agent must
        // do, so the more specific role has to win.
        assertTrue(
            roleColor(MergedElement(el(editable = true), "ui_tree")) !=
                roleColor(MergedElement(el(), "ui_tree")),
        )
    }

    @Test
    fun `a labelled but non-interactive node is passive, not tappable`() {
        // The regression that made this an explicit field: a section header like
        // "Saved networks" has a perfectly good label and is still not a target, so
        // passiveness cannot be inferred from a blank label.
        val header = roleColor(MergedElement(el(label = "Saved networks", interactive = false), "ui_tree"))
        val button = roleColor(MergedElement(el(label = "Join Prime", interactive = true), "ui_tree"))

        assertTrue(header != button)
    }

    @Test
    fun `checkable and scrollable each get their own colour`() {
        val plain = roleColor(MergedElement(el(), "ui_tree"))
        val toggle = roleColor(MergedElement(el(checked = false), "ui_tree"))
        val scroll = roleColor(MergedElement(el(scrollable = true), "ui_tree"))

        assertEquals(3, setOf(plain, toggle, scroll).size, "all three must be visually distinct")
    }
}
