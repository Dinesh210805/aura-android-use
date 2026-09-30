package com.aura.aura_ui.services

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When AURA's own run controls have to get out of the way of AURA's own gesture.
 *
 * The asymmetry is the whole point and every test here is really about it: a wrong LEAVE
 * silently swallows a tap and tells the agent it landed, while a wrong HIDE costs a blink.
 * So the uncertain cases must all come back HIDE.
 */
class OverlayEvasionTest {

    /** A 140×64dp-ish patch at the bottom centre of a 1080×2400 screen. */
    private val controls = OverlayEvasion.Bounds(left = 400, top = 2100, right = 680, bottom = 2260)

    private fun decide(vararg points: Pair<Int, Int>, pad: Int = 0) =
        OverlayEvasion.decide(controls, points.toList(), pad)

    // ── the case worth optimising ──────────────────────────────────────

    @Test
    fun `a tap nowhere near the controls leaves them alone`() {
        assertEquals(OverlayEvasion.Decision.LEAVE, decide(540 to 300))
    }

    @Test
    fun `a tap on the controls hides them`() {
        assertEquals(OverlayEvasion.Decision.HIDE, decide(540 to 2180))
    }

    @Test
    fun `a swipe that only ENDS on the controls still hides them`() {
        // Every point of the path is delivered, not just the first — a swipe finishing on
        // the Cancel button would end the agent's own run.
        assertEquals(OverlayEvasion.Decision.HIDE, decide(540 to 800, 540 to 2180))
    }

    @Test
    fun `a near miss hides once slop is allowed for`() {
        // 30px above the controls: clear on paper, reachable in practice once touch slop
        // is applied. A LEAVE here is exactly the silent failure this class exists to stop.
        assertEquals(OverlayEvasion.Decision.LEAVE, decide(540 to 2070, pad = 0))
        assertEquals(OverlayEvasion.Decision.HIDE, decide(540 to 2070, pad = 60))
    }

    // ── every uncertain case must hide ─────────────────────────────────

    @Test
    fun `unknown control bounds hide`() {
        // Null means "off screen OR not laid out yet" and this cannot tell them apart. Off
        // screen makes the hide a harmless no-op; not-yet-laid-out makes it necessary.
        assertEquals(
            OverlayEvasion.Decision.HIDE,
            OverlayEvasion.decide(bounds = null, points = listOf(540 to 300)),
        )
    }

    @Test
    fun `a gesture whose path could not be resolved hides`() {
        // Normalized coordinates, text-targeted elements and direction swipes all arrive
        // here as an empty list, because resolving them lives inside the injector.
        assertEquals(OverlayEvasion.Decision.HIDE, OverlayEvasion.decide(controls, emptyList()))
    }

    // ── boundaries ─────────────────────────────────────────────────────

    @Test
    fun `a point exactly on the edge counts as overlapping`() {
        assertEquals(OverlayEvasion.Decision.HIDE, decide(400 to 2100))
        assertEquals(OverlayEvasion.Decision.HIDE, decide(680 to 2260))
    }

    @Test
    fun `one pixel outside every edge is clear`() {
        assertEquals(OverlayEvasion.Decision.LEAVE, decide(399 to 2180))
        assertEquals(OverlayEvasion.Decision.LEAVE, decide(681 to 2180))
        assertEquals(OverlayEvasion.Decision.LEAVE, decide(540 to 2099))
        assertEquals(OverlayEvasion.Decision.LEAVE, decide(540 to 2261))
    }
}
