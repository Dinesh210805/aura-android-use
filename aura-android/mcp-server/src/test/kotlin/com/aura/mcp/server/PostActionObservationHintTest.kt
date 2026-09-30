package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `hint` field is the freshest, last, highest-priority text the model sees, so
 * what it says is closer to a behaviour switch than to documentation.
 *
 * Finding O-1: one blanket "do not look again after an action" is correct for a tap
 * and wrong for a scroll — traces showed the agent scrolling, being told not to look,
 * and concluding the content was not there. These pin the two apart.
 */
class PostActionObservationHintTest {

    @Test
    fun `scroll is told to look again, because the summary cannot show what scrolled in`() {
        for (tool in listOf("scroll_down", "scroll_up", "scroll_to", "swipe")) {
            val hint = PostActionObservation.hintFor(tool)
            assertTrue(hint.contains("read_screen"), "$tool must be pointed at a fresh look")
            assertFalse(
                hint.contains("do not call", ignoreCase = true),
                "$tool must not carry the blanket discouragement — that is finding O-1",
            )
        }
    }

    @Test
    fun `tap is still told to verify from the bundle rather than look again`() {
        val hint = PostActionObservation.hintFor("tap")
        assertTrue(hint.contains("do not call"))
        assertTrue(hint.contains("Verify the action from this"))
    }

    /**
     * Both branches must steer the cheap way first. Naming only perceive_screen here
     * would undo the demotion at the one position that outranks every other prompt
     * surface in the run.
     */
    @Test
    fun `every hint names read_screen for regrounding som_ids`() {
        for (tool in PostActionObservation.OBSERVED_TOOLS) {
            assertTrue(
                PostActionObservation.hintFor(tool).contains("read_screen"),
                "$tool's hint must name read_screen",
            )
        }
    }

    @Test
    fun `every hint warns that earlier som_ids are stale`() {
        for (tool in PostActionObservation.OBSERVED_TOOLS) {
            assertTrue(
                PostActionObservation.hintFor(tool).contains("som_ids are stale"),
                "$tool's hint must warn about stale som_ids",
            )
        }
    }
}
