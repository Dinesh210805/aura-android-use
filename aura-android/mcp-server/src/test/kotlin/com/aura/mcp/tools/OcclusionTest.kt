package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where a touch reaches an element once paint order is known, and when it cannot. */
class OcclusionTest {

    private fun node(x1: Int, y1: Int, x2: Int, y2: Int, z: Long?, zEnd: Long? = z, touch: Boolean = true) =
        Occlusion.Node(BBox(x1, y1, x2, y2), z, zEnd, touch)

    private val row = node(0, 1000, 1080, 1200, z = 10)

    @Test
    fun `without paint order an element keeps its centre`() {
        val v = Occlusion.resolve(listOf(node(0, 0, 100, 100, z = null), node(0, 0, 100, 100, z = null))).first()
        assertEquals(50 to 50, v.tap)
        assertNull(v.coveredBy)
    }

    @Test
    fun `a floating button over the centre moves the tap to the visible part`() {
        val fab = node(400, 950, 700, 1250, z = 40)
        val v = Occlusion.resolve(listOf(row, fab)).first()
        assertNull(v.coveredBy)
        assertTrue(v.tap.first !in 400 until 700, "tap ${v.tap} still under the button")
        assertTrue(v.tap.second in 1000 until 1200)
    }

    @Test
    fun `an element covered completely names its cover`() {
        val bar = node(0, 900, 1080, 1300, z = 50)
        val v = Occlusion.resolve(listOf(row, bar)).first()
        assertEquals(1, v.coveredBy)
    }

    @Test
    fun `a child inside the element never covers it`() {
        val wrapper = node(0, 1000, 1080, 1200, z = 10, zEnd = 12)
        val inner = node(0, 1000, 1080, 1200, z = 11)
        val v = Occlusion.resolve(listOf(wrapper, inner))
        assertNull(v[0].coveredBy)
        assertEquals(540 to 1100, v[0].tap)
    }

    @Test
    fun `a label drawn on top does not block the touch`() {
        val label = node(0, 1000, 1080, 1200, z = 60, touch = false)
        assertNull(Occlusion.resolve(listOf(row, label)).first().coveredBy)
    }

    @Test
    fun `something painted earlier never covers`() {
        val under = node(0, 0, 1080, 2400, z = 2)
        assertEquals(540 to 1100, Occlusion.resolve(listOf(row, under)).first().tap)
    }

    @Test
    fun `a sliver thinner than a fingertip counts as covered`() {
        val sheet = node(0, 1005, 1080, 1300, z = 50)
        assertNotEquals(null, Occlusion.resolve(listOf(row, sheet)).first().coveredBy)
    }

    @Test
    fun `two covers leave the free corner as the target`() {
        val left = node(0, 1000, 700, 1200, z = 20)
        val top = node(700, 1000, 1080, 1120, z = 21)
        val v = Occlusion.resolve(listOf(row, left, top)).first()
        assertNull(v.coveredBy)
        assertTrue(v.tap.first >= 700 && v.tap.second >= 1120, "tap ${v.tap} not in the free corner")
    }

    @Test
    fun `a dialog window covers the app below it even where the dialog is not clickable`() {
        val appButton = node(300, 1000, 780, 1100, z = 1_000_010)
        val dialog = node(100, 800, 980, 1400, z = 2_000_000, zEnd = 2_000_005, touch = false)
        assertEquals(1, Occlusion.resolve(listOf(appButton, dialog)).first().coveredBy)
    }

    @Test
    fun `a plain view in the same window does not count as a window`() {
        val appButton = node(300, 1000, 780, 1100, z = 1_000_010)
        val panel = node(100, 800, 980, 1400, z = 1_000_020, touch = false)
        assertNull(Occlusion.resolve(listOf(appButton, panel)).first().coveredBy)
    }

    // ── TouchTarget: which node the accessibility-click fallback reaches ──

    private fun cand(x1: Int, y1: Int, x2: Int, y2: Int, clickable: Boolean = true, enabled: Boolean = true, own: Boolean = false) =
        TouchTarget.Candidate(BBox(x1, y1, x2, y2), clickable, enabled, own)

    @Test
    fun `the topmost clickable wins over a deeper node painted earlier`() {
        val list = listOf(
            cand(0, 0, 1080, 2400, clickable = false), // screen
            cand(0, 1000, 1080, 1200),                 // deep row
            cand(400, 950, 700, 1250),                 // floating button, painted last
        )
        assertEquals(2, TouchTarget.pick(list, 500, 1100))
        assertEquals(1, TouchTarget.pick(list, 100, 1100))
    }

    @Test
    fun `AURA's own windows and disabled nodes are never picked`() {
        val list = listOf(cand(0, 0, 1080, 2400), cand(0, 0, 1080, 2400, own = true), cand(0, 0, 1080, 2400, enabled = false))
        assertEquals(0, TouchTarget.pick(list, 10, 10))
    }

    @Test
    fun `with no clickable node the topmost node of any kind is returned`() {
        val list = listOf(cand(0, 0, 100, 100, clickable = false), cand(0, 0, 50, 50, clickable = false))
        assertEquals(1, TouchTarget.pick(list, 10, 10))
        assertNull(TouchTarget.pick(list, 500, 500))
    }
}
