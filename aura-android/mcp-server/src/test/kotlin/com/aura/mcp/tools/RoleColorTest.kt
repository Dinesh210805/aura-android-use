package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The role colours `read_screen` draws its boxes in: what a box IS, readable from the picture. */
class RoleColorTest {

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

    @Test
    fun `editable outranks tappable`() {
        // An editable field is usually clickable too, and typing is what the agent must
        // do, so the more specific role has to win.
        assertTrue(
            roleColor(el(editable = true)) !=
                roleColor(el()),
        )
    }

    @Test
    fun `a labelled but non-interactive node is passive, not tappable`() {
        // The regression that made this an explicit field: a section header like
        // "Saved networks" has a perfectly good label and is still not a target, so
        // passiveness cannot be inferred from a blank label.
        val header = roleColor(el(label = "Saved networks", interactive = false))
        val button = roleColor(el(label = "Join Prime", interactive = true))

        assertTrue(header != button)
    }

    @Test
    fun `checkable and scrollable each get their own colour`() {
        val plain = roleColor(el())
        val toggle = roleColor(el(checked = false))
        val scroll = roleColor(el(scrollable = true))

        assertEquals(3, setOf(plain, toggle, scroll).size, "all three must be visually distinct")
    }
}
