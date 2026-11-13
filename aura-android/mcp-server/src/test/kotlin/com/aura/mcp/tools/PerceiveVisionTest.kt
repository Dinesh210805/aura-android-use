package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.cache.PerceptionCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerceiveVisionTest {

    private fun box(label: String, x: Int, y: Int, confidence: Float = 0.9f) =
        DetectedElement(somId = 0, bbox = BBox(x, y, x + 100, y + 60), elementType = "icon", label = label, confidence = confidence)

    @Test
    fun `vision boxes are numbered from 1001 in reading order`() {
        val boxes = numberVisionBoxes(listOf(box("Cart", 900, 2400), box("Search", 40, 200), box("Menu", 900, 200)))
        assertEquals(listOf("Search", "Menu", "Cart"), boxes.map { it.label })
        assertEquals(listOf(1001, 1002, 1003), boxes.map { it.somId })
        assertTrue(boxes.all { it.somId > PerceptionCache.VISION_ID_BASE })
    }

    @Test
    fun `each box is one line, unsure ones marked`() {
        val boxes = numberVisionBoxes(listOf(box("Search", 40, 200), box("", 900, 200, confidence = 0.3f)))
        val text = visionText("search bar", boxes, loading = false, offscreen = emptyList(), sameAsLast = false)
        assertEquals("VISION 2 boxes (looking for: search bar)\n1001 Search\n1002 ?", text)
    }
}
