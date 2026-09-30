package com.aura.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two judgement calls `read_screen` makes about a settled screen: whether the
 * agent may act on what came back, and whether it must escalate to vision instead.
 *
 * Kept pure and separate from the tool registration so both are testable without a
 * `Server`, a bridge, or a device.
 */
class ReadScreenTest {

    // ── escalation ──

    /**
     * The signal the stream format had no way to express. `stale` covers "could not
     * read the tree"; this covers "read fine, and it described nothing" — a WebView,
     * Canvas, Maps or game surface. Without it the agent gets a thin element list with
     * no indication it is looking at nothing, and taps into the void.
     */
    @Test
    fun `escalates when the tree describes fewer than the minimum elements`() {
        assertNotNullContains(ReadScreen.escalateReason(elementCount = 0, treeBlind = false), "0")
        assertNotNullContains(ReadScreen.escalateReason(elementCount = 2, treeBlind = false), "2")
    }

    @Test
    fun `does not escalate on a normal screen`() {
        assertNull(ReadScreen.escalateReason(elementCount = 3, treeBlind = false))
        assertNull(ReadScreen.escalateReason(elementCount = 180, treeBlind = false))
    }

    /**
     * A blind tree is the WebView/Canvas case even when it happens to report elements:
     * the tree stays constant while the surface moves freely, so its contents cannot be
     * trusted to describe what is on screen.
     */
    @Test
    fun `escalates on a tree-blind surface regardless of element count`() {
        assertNotNullContains(ReadScreen.escalateReason(elementCount = 50, treeBlind = true), "custom-rendered")
    }

    @Test
    fun `escalation reason names the tool to reach for`() {
        val reason = ReadScreen.escalateReason(elementCount = 1, treeBlind = false)
        assertTrue(reason!!.isNotBlank(), "an escalation with no reason teaches the agent nothing")
    }

    // ── idle ──

    /**
     * `idle` and `settled` answer different questions and the distinction is the whole
     * point of carrying both. `settled` = the screen stopped moving before the cap.
     * `idle` = it was never moving in the first place.
     */
    @Test
    fun `idle when the screen was already still and nothing fired`() {
        assertTrue(ReadScreen.idle(settled = true, eventCount = 0))
    }

    @Test
    fun `not idle when a transition ran, even though it settled`() {
        assertFalse(ReadScreen.idle(settled = true, eventCount = 7))
    }

    /** Cut short by the cap: still moving, so it cannot be idle whatever the events say. */
    @Test
    fun `not idle when the settle cap cut a moving screen short`() {
        assertFalse(ReadScreen.idle(settled = false, eventCount = 0))
        assertFalse(ReadScreen.idle(settled = false, eventCount = 30))
    }

    // ── the threshold is shared, not re-guessed ──

    /**
     * `read_screen` escalates on exactly the boundary `perceive_screen` uses to decide
     * the tree is unusable. Two different thresholds would mean a screen `read_screen`
     * called fine is one `perceive_screen` refuses to trust.
     */
    @Test
    fun `escalation boundary matches perceive_screen's minimum`() {
        assertNull(ReadScreen.escalateReason(MIN_UI_TREE_ELEMENTS, treeBlind = false))
        assertTrue(ReadScreen.escalateReason(MIN_UI_TREE_ELEMENTS - 1, treeBlind = false) != null)
    }

    private fun assertNotNullContains(actual: String?, needle: String) {
        assertTrue(actual != null, "expected an escalation reason, got null")
        assertTrue(actual.contains(needle), "expected \"$actual\" to mention \"$needle\"")
    }

    @Test
    fun `element count is stated in the reason so the agent can judge for itself`() {
        assertEquals(true, ReadScreen.escalateReason(2, treeBlind = false)!!.contains("2"))
    }
}
