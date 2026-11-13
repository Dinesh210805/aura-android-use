package com.aura.mcp.cache

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.tools.ScreenPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SomIdsTest {

    private fun el(label: String, x: Int, y: Int, w: Int = 300, h: Int = 120, type: String = "Button") =
        DetectedElement(somId = 0, bbox = BBox(x, y, x + w, y + h), elementType = type, label = label, confidence = 1f)

    /** A screen with a header, a send button and a carousel card that slides by [offset] px. */
    private fun chat(offset: Int, extra: List<DetectedElement> = emptyList()) = listOf(
        el("Amma", 0, 100, w = 1080, h = 150, type = "TextView"),
        el("Type a message", 40, 2100, w = 800, h = 120, type = "EditText"),
        el("Send", 900, 2100, w = 140, h = 120),
        el("Offer: 20% off", 40 + offset, 400, w = 900, h = 300, type = "Card"),
    ) + extra

    private fun idsByLabel(ids: SomIds, elements: List<DetectedElement>): Map<String, Int> {
        val rendered = ScreenPayload.render(elements, pkg = "com.whatsapp", idle = true, settled = true, ids = ids)!!
        return rendered.elements.associate { it.label to it.somId }
    }

    @Test
    fun `ids stay the same across ten looks at an animating screen`() {
        val ids = SomIds()
        val first = idsByLabel(ids, chat(0))
        repeat(10) { frame -> assertEquals(first, idsByLabel(ids, chat(offset = frame * 37))) }
        assertFalse(ids.lastWasNewScreen)
    }

    @Test
    fun `a counter ticking keeps its number`() {
        val ids = SomIds()
        val before = idsByLabel(ids, chat(0, listOf(el("3 unread", 40, 800, type = "TextView"))))
        val after = idsByLabel(ids, chat(0, listOf(el("4 unread", 40, 800, type = "TextView"))))
        assertEquals(before["3 unread"], after["4 unread"])
    }

    @Test
    fun `a new element gets a new number and nobody else moves`() {
        val ids = SomIds()
        val before = idsByLabel(ids, chat(0))
        val after = idsByLabel(ids, chat(0, listOf(el("Huge banner", 0, 300, w = 1080, h = 900, type = "Image"))))
        before.forEach { (label, id) -> assertEquals("$label kept its id", id, after[label]) }
        assertTrue(after.getValue("Huge banner") > before.values.max())
    }

    @Test
    fun `two rows with the same label stay apart`() {
        val ids = SomIds()
        val rendered = ScreenPayload.render(chat(0, listOf(el("Reply", 40, 900), el("Reply", 40, 1100))), "com.whatsapp", true, true, ids = ids)!!
        val replyIds = rendered.elements.filter { it.label == "Reply" }.map { it.somId }
        assertEquals(2, replyIds.toSet().size)
    }

    @Test
    fun `a different screen renumbers`() {
        val ids = SomIds()
        idsByLabel(ids, chat(0))
        val settings = listOf(
            el("Wi-Fi", 0, 200, w = 1080), el("Bluetooth", 0, 400, w = 1080),
            el("Battery", 0, 600, w = 1080), el("Display", 0, 800, w = 1080),
        )
        val renumbered = idsByLabel(ids, settings)
        assertTrue(ids.lastWasNewScreen)
        assertEquals((1..4).toSet(), renumbered.values.toSet())
        // Another app with the same labels is a new screen too.
        idsByLabel(ids, settings)
        assertFalse(ids.lastWasNewScreen)
        ScreenPayload.render(settings, pkg = "com.android.settings.other", idle = true, settled = true, ids = ids)
        assertTrue(ids.lastWasNewScreen)
    }

    @Test
    fun `the first look numbers by area, largest first`() {
        val rendered = ScreenPayload.render(chat(0), pkg = "com.whatsapp", idle = true, settled = true, ids = SomIds())!!
        assertEquals(1, rendered.elements.first { it.label == "Offer: 20% off" }.somId)
        assertNotEquals(1, rendered.elements.first { it.label == "Send" }.somId)
    }
}
