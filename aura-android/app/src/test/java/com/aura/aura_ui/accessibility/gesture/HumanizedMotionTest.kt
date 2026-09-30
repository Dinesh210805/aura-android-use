package com.aura.aura_ui.accessibility.gesture

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanizedMotionTest {

    @Test
    fun `tap jitter stays within bounds and respects floor`() {
        repeat(200) { seed ->
            val plan = HumanizedMotion.planTap(
                x = 500, y = 900, durationMs = 100L, floorMs = 50L, random = Random(seed),
            )
            assertTrue(
                "x jitter out of bounds: ${plan.x}",
                abs(plan.x - 500) <= HumanizedMotion.JITTER_PX,
            )
            assertTrue(
                "y jitter out of bounds: ${plan.y}",
                abs(plan.y - 900) <= HumanizedMotion.JITTER_PX,
            )
            assertTrue("duration below floor: ${plan.durationMs}", plan.durationMs >= 50L)
        }
    }

    @Test
    fun `tap duration never drops below floor even for tiny requested duration`() {
        val plan = HumanizedMotion.planTap(
            x = 0, y = 0, durationMs = 10L, floorMs = 50L, random = Random(1),
        )
        assertTrue(plan.durationMs >= 50L)
    }

    @Test
    fun `stroke produces the configured number of segments joined end to end`() {
        val plan = HumanizedMotion.planStroke(
            startX = 100, startY = 200, endX = 100, endY = 1000,
            durationMs = 300L, floorMs = 100L,
            profile = HumanizedMotion.Profile.ACCELERATE, random = Random(7),
        )
        assertEquals(HumanizedMotion.SEGMENT_COUNT, plan.segments.size)
        // First segment starts at the start point; last ends at the end point.
        assertEquals(100f, plan.segments.first().fromX, 0.01f)
        assertEquals(200f, plan.segments.first().fromY, 0.01f)
        assertEquals(100f, plan.segments.last().toX, 0.5f)
        assertEquals(1000f, plan.segments.last().toY, 0.5f)
        // Segments are contiguous.
        for (i in 0 until plan.segments.size - 1) {
            assertEquals(plan.segments[i].toX, plan.segments[i + 1].fromX, 0.001f)
            assertEquals(plan.segments[i].toY, plan.segments[i + 1].fromY, 0.001f)
        }
    }

    @Test
    fun `segment durations sum exactly to requested total`() {
        val total = 275L
        val plan = HumanizedMotion.planStroke(
            startX = 0, startY = 0, endX = 800, endY = 50,
            durationMs = total, floorMs = 100L,
            profile = HumanizedMotion.Profile.ACCELERATE, random = Random(3),
        )
        assertEquals(total, plan.segments.sumOf { it.durationMs })
    }

    @Test
    fun `accelerate profile releases faster than it starts`() {
        val plan = HumanizedMotion.planStroke(
            startX = 0, startY = 0, endX = 0, endY = 1000,
            durationMs = 400L, floorMs = 100L,
            profile = HumanizedMotion.Profile.ACCELERATE, random = Random(11),
        )
        // Equal-length segments: last takes less time than first → faster at release.
        assertTrue(
            "expected release segment faster than start",
            plan.segments.last().durationMs < plan.segments.first().durationMs,
        )
    }

    @Test
    fun `ease in out is slower at the ends than in the middle`() {
        val plan = HumanizedMotion.planStroke(
            startX = 0, startY = 0, endX = 1000, endY = 0,
            durationMs = 400L, floorMs = 100L,
            profile = HumanizedMotion.Profile.EASE_IN_OUT, random = Random(5),
        )
        val first = plan.segments.first().durationMs
        val last = plan.segments.last().durationMs
        val middle = plan.segments[plan.segments.size / 2].durationMs
        assertTrue("ends should be slower than middle", first > middle && last > middle)
    }

    @Test
    fun `curved path bulges off the straight line`() {
        val plan = HumanizedMotion.planStroke(
            startX = 0, startY = 0, endX = 0, endY = 1000,
            durationMs = 300L, floorMs = 100L,
            profile = HumanizedMotion.Profile.ACCELERATE, random = Random(2),
        )
        // A straight vertical line would keep every x at 0; the arc must deviate.
        val maxX = plan.segments.maxOf { abs(it.toX) }
        assertTrue("expected lateral curve, got maxX=$maxX", maxX > 1f)
    }
}
