package com.aura.aura_ui.presentation.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the marquee curve. The sign of [MarqueeGeometry.curveDrop] is the
 * one thing here that can be wrong while still looking plausible in a preview —
 * y = -0.3x² peaks at the centre in maths space, and Compose's y grows downward,
 * so the rendered offset must be POSITIVE (lower on screen) at the edges.
 */
class MarqueeGeometryTest {

    @Test
    fun `curve apex sits at the centre`() {
        assertEquals(0f, MarqueeGeometry.curveDrop(0f, 100f), 1e-4f)
    }

    @Test
    fun `edges are pushed down not up`() {
        val edge = MarqueeGeometry.curveDrop(1f, 100f)
        assertTrue("edge must sit below the apex, was $edge", edge > 0f)
        assertEquals(30f, edge, 1e-3f) // 0.3 × amplitude
    }

    @Test
    fun `curve is symmetric about the centre`() {
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { x ->
            assertEquals(
                MarqueeGeometry.curveDrop(x, 120f),
                MarqueeGeometry.curveDrop(-x, 120f),
                1e-4f,
            )
        }
    }

    @Test
    fun `curve is quadratic not linear`() {
        // Doubling x quadruples the drop — a linear ramp would only double it.
        val quarter = MarqueeGeometry.curveDrop(0.5f, 100f)
        val full = MarqueeGeometry.curveDrop(1f, 100f)
        assertEquals(4f, full / quarter, 1e-3f)
    }

    @Test
    fun `signed delta takes the short way round the ring`() {
        // Slot 5 of 6 is one step BEHIND position 0, not five steps ahead.
        assertEquals(-1f, MarqueeGeometry.signedDelta(index = 5, position = 0f, count = 6), 1e-4f)
        assertEquals(1f, MarqueeGeometry.signedDelta(index = 1, position = 0f, count = 6), 1e-4f)
        assertEquals(0f, MarqueeGeometry.signedDelta(index = 3, position = 3f, count = 6), 1e-4f)
    }

    @Test
    fun `signed delta stays within half a ring`() {
        val count = 9
        for (index in 0 until count) {
            var position = 0f
            while (position < count) {
                val d = MarqueeGeometry.signedDelta(index, position, count)
                assertTrue("delta $d out of range for index=$index pos=$position", d > -count / 2f - 1e-3f && d <= count / 2f + 1e-3f)
                position += 0.37f
            }
        }
    }

    @Test
    fun `signed delta is safe on an empty ring`() {
        assertEquals(0f, MarqueeGeometry.signedDelta(index = 0, position = 2f, count = 0), 1e-4f)
        assertEquals(0, MarqueeGeometry.centredIndex(position = 2f, count = 0))
    }

    @Test
    fun `centred index wraps in both directions`() {
        assertEquals(0, MarqueeGeometry.centredIndex(position = 0f, count = 5))
        assertEquals(2, MarqueeGeometry.centredIndex(position = 7f, count = 5))
        assertEquals(4, MarqueeGeometry.centredIndex(position = -1f, count = 5))
        // Mid-travel rounds to the nearer neighbour.
        assertEquals(3, MarqueeGeometry.centredIndex(position = 2.6f, count = 5))
    }

    @Test
    fun `continuous position wraps after a fling crosses either end`() {
        assertEquals(0f, MarqueeGeometry.wrapPosition(10f, count = 5), 1e-4f)
        assertEquals(1.5f, MarqueeGeometry.wrapPosition(6.5f, count = 5), 1e-4f)
        assertEquals(3.5f, MarqueeGeometry.wrapPosition(-1.5f, count = 5), 1e-4f)
        assertEquals(0f, MarqueeGeometry.wrapPosition(2f, count = 0), 1e-4f)
    }

    @Test
    fun `logos shrink and fade away from the centre but never vanish`() {
        assertEquals(1f, MarqueeGeometry.scaleAt(0f), 1e-4f)
        assertEquals(1f, MarqueeGeometry.alphaAt(0f), 1e-4f)
        assertTrue(MarqueeGeometry.scaleAt(1f) < MarqueeGeometry.scaleAt(0.5f))
        assertTrue(MarqueeGeometry.alphaAt(1f) < MarqueeGeometry.alphaAt(0.5f))
        assertTrue(MarqueeGeometry.scaleAt(4f) >= 0.45f)
        assertTrue(MarqueeGeometry.alphaAt(4f) >= 0f)
    }

    @Test
    fun `caption is solid when settled and gone mid travel`() {
        assertEquals(1f, MarqueeGeometry.captionAlpha(3f), 1e-4f)
        assertEquals(0f, MarqueeGeometry.captionAlpha(3.5f), 1e-4f)
        assertTrue(MarqueeGeometry.captionAlpha(3.15f) < 1f)
    }

    @Test
    fun `every curated app carries a prompt and at least one package`() {
        CURATED_APPS.forEach { app ->
            assertTrue("${app.label} has no prompt", app.prompt.isNotBlank())
            assertTrue("${app.label} has no packages", app.packages.isNotEmpty())
        }
        // Package names are the join key against PackageManager — duplicates
        // would render the same app twice in the band.
        val packages = CURATED_APPS.flatMap { it.packages }
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun `fallback prompts exist so the band is never empty`() {
        assertTrue(FALLBACK_PROMPTS.isNotEmpty())
        assertTrue(FALLBACK_PROMPTS.all { it.isNotBlank() })
    }
}
