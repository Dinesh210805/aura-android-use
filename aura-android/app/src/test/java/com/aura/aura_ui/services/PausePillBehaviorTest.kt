package com.aura.aura_ui.services

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The on-screen run controls (2026-09-07 rewrite).
 *
 * The old suite pinned a draggable pill that shrank to a dot and needed two taps to
 * resume. That design is gone — see [PausePillBehavior]'s KDoc for why the premise it
 * rested on stopped being true. What survives is the one rule worth pinning: **Cancel is
 * reachable only from a paused run.**
 */
class PausePillBehaviorTest {

    @Test
    fun `no controls when no local run has the wheel`() {
        assertEquals(
            PausePillBehavior.Controls.HIDDEN,
            PausePillBehavior.controlsFor(localRunActive = false, paused = false),
        )
    }

    @Test
    fun `a running agent offers pause only`() {
        // Cancel under a moving run is one thumb-brush away from discarding the work.
        assertEquals(
            PausePillBehavior.Controls.PAUSE_ONLY,
            PausePillBehavior.controlsFor(localRunActive = true, paused = false),
        )
    }

    @Test
    fun `pausing reveals cancel alongside resume`() {
        assertEquals(
            PausePillBehavior.Controls.RESUME_AND_CANCEL,
            PausePillBehavior.controlsFor(localRunActive = true, paused = true),
        )
    }

    @Test
    fun `a stale pause with no run showing still hides the controls`() {
        // The lock can outlive the run that armed it. Controls for a run that is not there
        // would offer a Resume that resumes nothing.
        assertEquals(
            PausePillBehavior.Controls.HIDDEN,
            PausePillBehavior.controlsFor(localRunActive = false, paused = true),
        )
    }

    @Test
    fun `the controls clear the navigation bar`() {
        assertEquals(120, PausePillBehavior.bottomMarginPx(navBarInsetPx = 120, minimumPx = 48))
    }

    @Test
    fun `a device reporting no navigation inset still gets a margin`() {
        // A gesture-nav device can report 0 while still swallowing touches at the very
        // bottom edge. The floor is what keeps Resume pressable there.
        assertEquals(48, PausePillBehavior.bottomMarginPx(navBarInsetPx = 0, minimumPx = 48))
    }
}
