package com.aura.aura_ui.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rects describe something the user can actually see.
 *
 * Android reports `getBoundsInScreen` CLIPPED to the visible parent. A child that
 * has been scrolled out of its viewport therefore does not come back with its real
 * off-viewport rect — it comes back COLLAPSED: zero width pinned to the viewport's
 * edge, or zero height. Measured on the Amazon home screen (1240x2772), 170 of 331
 * emitted nodes were such collapses — 102 of them at exactly `left = right = 1241`,
 * one pixel past the screen — every single one carrying real text from a carousel
 * card scrolled off to the right ("Snapdragon 8 Elite processor", "Min. 55% off").
 *
 * They are invisible and untappable, so they must not reach the payload. The trap is
 * that they are *labeled*, which makes them look like exactly the content that must
 * be preserved — so the discriminator has to be GEOMETRY, never text-bearing-ness.
 */
class VisibleBoundsTest {

    private val W = 1240
    private val H = 2772

    @Test
    fun `an ordinary on-screen rect is visible`() {
        assertTrue(VisibleBounds.isVisible(98, 2212, 469, 2289, W, H))
    }

    @Test
    fun `a rect touching the screen edges is visible`() {
        assertTrue(VisibleBounds.isVisible(0, 0, W, H, W, H))
    }

    @Test
    fun `a partially off-screen rect is still visible`() {
        // The "Pharmacy" tile runs past the right edge but is half on screen.
        assertTrue(VisibleBounds.isVisible(1139, 311, 1400, 367, W, H))
    }

    // ── the collapse signatures ──────────────────────────────────────────────

    @Test
    fun `zero width is not visible`() {
        assertFalse(VisibleBounds.isVisible(1241, 840, 1241, 1085, W, H))
    }

    @Test
    fun `zero height is not visible`() {
        assertFalse(VisibleBounds.isVisible(14, 900, 241, 900, W, H))
    }

    @Test
    fun `an inverted rect is not visible`() {
        // Rect.width() returns right - left and CAN be negative; a `>= 0` guard
        // would wave this through.
        assertFalse(VisibleBounds.isVisible(500, 400, 300, 600, W, H))
        assertFalse(VisibleBounds.isVisible(300, 600, 500, 400, W, H))
    }

    @Test
    fun `a rect entirely past the right edge is not visible`() {
        assertFalse(VisibleBounds.isVisible(1300, 840, 1600, 1085, W, H))
    }

    @Test
    fun `a rect entirely above the screen is not visible`() {
        assertFalse(VisibleBounds.isVisible(100, -500, 400, 0, W, H))
    }

    @Test
    fun `a rect entirely below the screen is not visible`() {
        assertFalse(VisibleBounds.isVisible(100, H, 400, H + 300, W, H))
    }

    @Test
    fun `a rect entirely left of the screen is not visible`() {
        assertFalse(VisibleBounds.isVisible(-400, 100, 0, 300, W, H))
    }

    // ── unknown screen size must not blind the walk ──────────────────────────

    @Test
    fun `with no screen size the off-screen test is skipped but empties still fail`() {
        // ScreenGeometry returns 0x0 when the display is unavailable. Rejecting
        // everything then would blank the tree; rejecting empties still must hold.
        assertTrue(VisibleBounds.isVisible(1300, 840, 1600, 1085, 0, 0))
        assertFalse(VisibleBounds.isVisible(1241, 840, 1241, 1085, 0, 0))
    }
}
