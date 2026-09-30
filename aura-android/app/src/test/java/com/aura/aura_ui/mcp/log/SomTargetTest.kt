package com.aura.aura_ui.mcp.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which invocation a reported SoM image is filed under.
 *
 * The device found this: a whole Spotify run (3 gestures, vision strategy live) produced no
 * `som/` directory at all. The model never called a look tool — the A6 observation after each
 * gesture fed it the screen — and the target search only ever considered `perceive_screen` /
 * `get_screenshot`, so every image was dropped on the floor. What the model actually saw is the
 * one artifact a trace is opened for, so "no look tool this turn" must not mean "no image".
 */
class SomTargetTest {

    private fun inv(index: Int, tool: String, som: Boolean = false) =
        ToolInvocation(index = index, timestampMillis = index.toLong(), toolName = tool, argsJson = null)
            .also { it.somImage = som }

    @Test
    fun `a waiting look tool is preferred over later bookkeeping`() {
        val invocations = listOf(
            inv(0, "perceive_screen"),
            inv(1, "mark_step"),
        )
        assertEquals(0, somTargetIn(invocations)?.index)
    }

    @Test
    fun `read_screen counts as a look tool`() {
        // read_screen is the DEFAULT look per Doctrine, and was absent from the original set.
        val invocations = listOf(inv(0, "launch_app"), inv(1, "read_screen"))
        assertEquals(1, somTargetIn(invocations)?.index)
    }

    @Test
    fun `an image still lands when the model never called a look tool`() {
        // The regression: this is the exact shape of the Spotify run.
        val invocations = listOf(
            inv(0, "set_plan"),
            inv(1, "launch_app"),
            inv(2, "mark_step"),
            inv(3, "tap"),
        )
        assertEquals("the screen after the gesture belongs to the gesture", 3, somTargetIn(invocations)?.index)
    }

    @Test
    fun `an invocation that already has its image is never overwritten`() {
        val invocations = listOf(
            inv(0, "perceive_screen", som = true),
            inv(1, "tap"),
        )
        assertEquals(1, somTargetIn(invocations)?.index)
    }

    @Test
    fun `every invocation already filed means there is nothing to target`() {
        val invocations = listOf(inv(0, "perceive_screen", som = true), inv(1, "tap", som = true))
        assertNull(somTargetIn(invocations))
    }

    @Test
    fun `no invocations yet means nothing to target`() {
        assertNull(somTargetIn(emptyList()))
    }
}
