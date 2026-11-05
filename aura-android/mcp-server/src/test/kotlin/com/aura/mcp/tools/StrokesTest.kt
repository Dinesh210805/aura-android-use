package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StrokesTest {

    private fun parse(json: String, box: BBox? = null) = Strokes.parse(Json.parseToJsonElement(json), box)

    @Test
    fun `pixel strokes come back in order, rounded`() {
        val ok = assertIs<Strokes.Parsed.Ok>(parse("[[[10, 20], [30.6, 40]], [[5, 5]]]"))
        assertEquals(listOf(listOf(10 to 20, 31 to 40), listOf(5 to 5)), ok.strokes)
    }

    @Test
    fun `with an element, points are fractions of its box`() {
        val canvas = BBox(100, 1000, 300, 1400)
        val ok = assertIs<Strokes.Parsed.Ok>(parse("[[[0, 0], [0.5, 0.5], [1, 1]]]", canvas))
        assertEquals(listOf(100 to 1000, 200 to 1200, 300 to 1400), ok.strokes.single())
    }

    @Test
    fun `a fraction outside 0 to 1 is refused, not clamped`() {
        assertIs<Strokes.Parsed.Error>(parse("[[[0.5, 1.2]]]", BBox(0, 0, 100, 100)))
    }

    @Test
    fun `malformed input is refused`() {
        listOf("[]", "[[]]", "[[[1]]]", "[[[-1, 5]]]", "[[\"a\"]]", "{}", "[[[1, \"x\"]]]").forEach {
            assertIs<Strokes.Parsed.Error>(parse(it), "should refuse $it")
        }
    }

    @Test
    fun `too many strokes is refused`() {
        val many = (0..Strokes.MAX_STROKES).joinToString(",", "[", "]") { "[[1, 1]]" }
        assertIs<Strokes.Parsed.Error>(parse(many))
    }

    @Test
    fun `a drawing longer than a minute is refused`() {
        assertNull(Strokes.checkTiming(10, 5_000, 1_000))
        assertNotNull(Strokes.checkTiming(20, 3_000, 1))
    }
}
