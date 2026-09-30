package com.aura.aura_ui.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility payload is the agent's only factual view of the screen.
 * Anything the extractor does not serialize is invisible downstream, no matter
 * how good the selection policy in `:mcp-server` is.
 *
 * `isCheckable` / `isChecked` were absent from the payload entirely, so a
 * Wi-Fi row that was already ON looked identical to one that was OFF and the
 * agent would toggle it the wrong way. `isLongClickable` was likewise dropped,
 * hiding long-press-only affordances.
 */
class UiElementSerializationTest {

    private fun element(
        text: String = "",
        checkable: Boolean = false,
        checked: Boolean = false,
        longClickable: Boolean = false,
    ) = UIElementData(
        text = text,
        contentDescription = null,
        bounds = BoundsData(995, 412, 1128, 496, 1061, 454, 133, 84),
        className = "android.widget.Switch",
        isClickable = false,
        isScrollable = false,
        isEditable = false,
        isCheckable = checkable,
        isChecked = checked,
        isLongClickable = longClickable,
        isEnabled = true,
        isFocused = false,
        actions = emptyList(),
        packageName = "com.oplus.wirelesssettings",
        viewId = "android:id/switch_widget",
    )

    @Test
    fun `serializes checkable state so a toggle's position is visible to the agent`() {
        val map = element(text = "On", checkable = true, checked = true).toTreeMap()

        assertEquals(true, map["isCheckable"])
        assertEquals(true, map["isChecked"])
    }

    @Test
    fun `serializes an off switch as checked false rather than omitting it`() {
        val map = element(checkable = true, checked = false).toTreeMap()

        assertEquals(true, map["isCheckable"])
        assertEquals(false, map["isChecked"])
    }

    @Test
    fun `serializes long-clickable so long-press-only affordances survive`() {
        val map = element(longClickable = true).toTreeMap()

        assertEquals(true, map["isLongClickable"])
    }

    @Test
    fun `keeps the pre-existing payload keys intact`() {
        // Downstream parsers (UiTreeToElements, UiTreeHeuristics, verify_action,
        // wait_for) read these by name — renaming or dropping one breaks them
        // silently, so pin the contract.
        val map = element(text = "On").toTreeMap()

        val required = listOf(
            "text", "contentDescription", "className", "bounds",
            "isClickable", "isScrollable", "isEditable", "isEnabled",
            "isFocused", "actions", "viewId",
        )
        required.forEach { key ->
            assertTrue("payload lost the '$key' key", map.containsKey(key))
        }
    }

    @Test
    fun `bounds are serialized as the nested object downstream expects`() {
        @Suppress("UNCHECKED_CAST")
        val bounds = element().toTreeMap()["bounds"] as Map<String, Int>

        assertEquals(995, bounds["left"])
        assertEquals(412, bounds["top"])
        assertEquals(1128, bounds["right"])
        assertEquals(496, bounds["bottom"])
        assertEquals(1061, bounds["centerX"])
        assertEquals(454, bounds["centerY"])
    }
}
