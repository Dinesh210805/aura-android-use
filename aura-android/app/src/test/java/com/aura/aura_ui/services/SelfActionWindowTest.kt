package com.aura.aura_ui.services

import com.aura.aura_ui.services.SelfActionWindow.ActionKind
import com.aura.aura_ui.services.SelfActionWindow.Dispatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 2026-07-31 — the *self-action window*.
 *
 * The problem it solves: AURA cannot ask Android "did a human touch the screen?". It can
 * only observe *effects* — accessibility events, window changes. But AURA's own actions
 * produce exactly the same effects. Without a way to tell them apart, the agent taps a
 * button, sees the resulting event, concludes "the user grabbed their phone", and pauses
 * itself. Every single action. The feature would be unusable.
 *
 * Getting it wrong is expensive in both directions: too narrow and AURA pauses on itself,
 * too wide and a real touch is swallowed while AURA keeps driving a phone its owner has
 * already taken back. Ties break toward the human.
 */
class SelfActionWindowTest {

    @Test
    fun `an event moments after AURA acted is AURA's own`() {
        assertTrue(SelfActionWindow.isSelfCaused(eventAtMs = 1_200, dispatch = Dispatch(1_000, ActionKind.TAP)))
    }

    @Test
    fun `an event long after AURA acted is a human`() {
        assertFalse(SelfActionWindow.isSelfCaused(eventAtMs = 9_000, dispatch = Dispatch(1_000, ActionKind.TAP)))
    }

    @Test
    fun `with nothing dispatched yet, nothing is AURA's own`() {
        assertFalse(SelfActionWindow.isSelfCaused(eventAtMs = 1_000, dispatch = null))
    }

    @Test
    fun `an event that predates the dispatch is not its effect`() {
        // Event queues and clocks are not perfectly ordered. Attributing an earlier event
        // to a later action would silently swallow a real touch.
        assertFalse(SelfActionWindow.isSelfCaused(eventAtMs = 900, dispatch = Dispatch(1_000, ActionKind.TAP)))
    }

    @Test
    fun `the boundary belongs to the human`() {
        assertFalse(
            SelfActionWindow.isSelfCaused(
                eventAtMs = 1_000 + ActionKind.TAP.settleMs,
                dispatch = Dispatch(1_000, ActionKind.TAP),
            ),
        )
    }

    // ── The bug this design exists to fix (2026-08-03) ───────────────────────

    @Test
    fun `a fling still settling after AURA's own scroll is not a human`() {
        // THE regression. A scroll gesture is itself several hundred ms, and when the
        // finger lifts the list keeps moving under its own momentum, emitting
        // TYPE_VIEW_SCROLLED for seconds. Under the old single 800 ms window that tail
        // fell outside the window and read as a person flicking the screen — so every
        // scroll AURA performed paused AURA.
        val flingTail = 1_000 + 2_000L
        assertTrue(
            "AURA paused on the tail of its own scroll",
            SelfActionWindow.isSelfCaused(flingTail, Dispatch(1_000, ActionKind.SCROLL)),
        )
    }

    @Test
    fun `the same delay after a tap IS a human`() {
        // The other half of the same point: the fix is not "make the window bigger". A tap
        // has no tail, so two seconds later is unambiguously a person — and widening one
        // global constant to cover a fling would have blinded AURA to real touches for
        // seconds after every click.
        assertFalse(
            SelfActionWindow.isSelfCaused(eventAtMs = 1_000 + 2_000L, dispatch = Dispatch(1_000, ActionKind.TAP)),
        )
    }

    // ── Chained attribution (2026-08-05) ─────────────────────────────────────

    @Test
    fun `a navigating tap keeps owning its effects past the tap window`() {
        // THE bug from the 2026-08-05 device traces. AURA taps "Save"; the destination
        // screen paints for ~2s, emitting VIEW_SCROLLED / VIEW_TEXT_CHANGED the whole
        // way. Those are PauseTrigger.HUMAN_SIGNALS, and they land past TAP's 800ms
        // window — so AURA paused itself with its own Save button, then had three
        // end_session calls refused.
        var d = Dispatch(1_000, ActionKind.TAP)
        // Effects arrive every 300ms — inside the quiet gap, so each chains to the last.
        for (t in longArrayOf(1_300, 1_600, 1_900, 2_200, 2_500)) {
            val a = SelfActionWindow.attribute(t, d)
            assertTrue("event at $t was misread as a human", a is SelfActionWindow.Attribution.SelfCaused)
            d = (a as SelfActionWindow.Attribution.SelfCaused).updated
        }
    }

    @Test
    fun `a gap longer than the quiet window breaks the chain`() {
        // The other half. Chaining must not become "AURA owns the screen forever":
        // once the device goes quiet, the next event is a person.
        // Chain far enough that TAP's own 800ms initial grace is spent — otherwise the
        // grace, not the chain, is what keeps the event self-caused and this asserts
        // nothing. (First draft of this test got exactly that wrong.)
        var d = Dispatch(1_000, ActionKind.TAP)
        for (t in longArrayOf(1_300, 1_600)) {
            d = (SelfActionWindow.attribute(t, d) as SelfActionWindow.Attribution.SelfCaused).updated
        }
        val afterQuiet = 1_600 + SelfActionWindow.QUIET_GAP_MS + 1   // 1951: past the 800ms grace
        assertTrue(SelfActionWindow.attribute(afterQuiet, d) is SelfActionWindow.Attribution.Human)
    }

    @Test
    fun `the per-kind ceiling ends the chain even under continuous events`() {
        // A self-animating screen (carousel, live feed) emits forever. Without a ceiling
        // the chain would suppress every human touch for the rest of the run.
        var d = Dispatch(0, ActionKind.TAP)
        var t = 0L
        var sawHuman = false
        repeat(100) {
            t += 300
            when (val a = SelfActionWindow.attribute(t, d)) {
                is SelfActionWindow.Attribution.SelfCaused -> d = a.updated
                is SelfActionWindow.Attribution.Human -> sawHuman = true
            }
        }
        assertTrue("the chain never terminated — a human could never regain the phone", sawHuman)
        assertTrue(t > ActionKind.TAP.maxSelfMs)
    }

    @Test
    fun `ceilings are ordered like the settle times they cap`() {
        assertTrue(ActionKind.TAP.maxSelfMs <= ActionKind.SCROLL.maxSelfMs)
        assertTrue(ActionKind.SCROLL.maxSelfMs < ActionKind.LAUNCH.maxSelfMs)
        // A ceiling below its own initial grace would be incoherent.
        ActionKind.entries.forEach { assertTrue(it.maxSelfMs >= it.settleMs) }
    }

    @Test
    fun `an unchained event two seconds after a tap is still a human`() {
        // Guards the existing contract: chaining requires intermediate effects. A lone
        // event with nothing between it and the dispatch stays a human touch.
        assertTrue(
            SelfActionWindow.attribute(3_000, Dispatch(1_000, ActionKind.TAP))
                is SelfActionWindow.Attribution.Human,
        )
    }

    @Test
    fun `settle times are ordered by how long each action really echoes`() {
        // Encodes the reasoning rather than the numbers: a tap is instantaneous, typing
        // can trickle as a field reformats, a fling decays, a cold start paints for
        // seconds. If a future edit makes a tap's window outlast a scroll's, the model
        // behind these numbers has been lost.
        assertTrue(ActionKind.TAP.settleMs < ActionKind.TYPE.settleMs)
        assertTrue(ActionKind.TYPE.settleMs < ActionKind.SCROLL.settleMs)
        assertTrue(ActionKind.SCROLL.settleMs < ActionKind.LAUNCH.settleMs)
    }
}
