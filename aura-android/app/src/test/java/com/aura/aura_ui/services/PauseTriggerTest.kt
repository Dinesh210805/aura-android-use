package com.aura.aura_ui.services

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 2026-07-31 — which observed events mean *a human is using this phone*.
 *
 * [SelfActionWindow] answers "did AURA cause this?". It cannot answer "did *anybody*
 * cause this?", and that gap is where a naive pause implementation dies:
 * `onAccessibilityEvent` fires for notifications arriving, background apps updating,
 * content refreshing, and AURA's own overlay animating. Treat all of it as a human and
 * the agent pauses on ambient noise until the product does nothing at all.
 *
 * So the trigger is an explicit allow-list of events that a *person* produces by
 * touching the screen.
 */
class PauseTriggerTest {

    @Test
    fun `a tap is a human`() {
        assertTrue(PauseTrigger.isHumanSignal(AccessibilityEvent.TYPE_VIEW_CLICKED, fromOwnApp = false))
    }

    @Test
    fun `a long press is a human`() {
        assertTrue(PauseTrigger.isHumanSignal(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, fromOwnApp = false))
    }

    @Test
    fun `a touch interaction is a human`() {
        assertTrue(
            PauseTrigger.isHumanSignal(
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                fromOwnApp = false,
            ),
        )
    }

    @Test
    fun `focus moving is not a human`() {
        // CORRECTED. This was in the trigger set and it is pure noise: focus changes on
        // every screen transition and every keyboard show. The service is registered with
        // `typeAllMask`, so it sees all of it — one stray focus event was enough to latch
        // the lock for the process lifetime.
        assertFalse(PauseTrigger.isHumanSignal(AccessibilityEvent.TYPE_VIEW_FOCUSED, fromOwnApp = false))
    }

    @Test
    fun `text changing is not a human`() {
        // Tempting, because a person typing produces it. But so does every programmatic
        // setText on the device: chat messages arriving, timers ticking, counters
        // updating — and AURA's own type_text. The comment on the previous version of this
        // test asked for "a device pass to show what a real finger actually emits"; that
        // pass ran on 2026-08-05 and the answer was that this event cannot carry the
        // distinction.
        assertFalse(
            PauseTrigger.isHumanSignal(
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                fromOwnApp = false,
            ),
        )
    }

    @Test
    fun `a list settling after AURA's own tap is not a human`() {
        // THE regression this file exists to prevent (CPH2661, 2026-08-05). A single
        // session recorded 13 false pauses and every one was TYPE_VIEW_SCROLLED emitted by
        // WhatsApp settling after AURA's own tap, 1457–3036ms after dispatch. Not one was
        // a real touch.
        //
        // Two attempts to filter these by timing both failed, because "a list finishing its
        // animation" and "a person flicking the screen" are the same event. The invariant:
        // a human interacts by TOUCHING, and a screen changing on its own is not an
        // interaction.
        assertFalse(PauseTrigger.isHumanSignal(AccessibilityEvent.TYPE_VIEW_SCROLLED, fromOwnApp = false))
    }

    @Test
    fun `a real tap still pauses the agent`() {
        // The other side of the trade. Removing SCROLLED/TEXT_CHANGED means a person who
        // scrolls WITHOUT tapping will not pause the agent — but their first tap must, or
        // the feature does nothing at all.
        assertTrue(PauseTrigger.isHumanSignal(AccessibilityEvent.TYPE_VIEW_CLICKED, fromOwnApp = false))
        assertTrue(PauseTrigger.isHumanSignal(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, fromOwnApp = false))
    }

    @Test
    fun `a notification arriving is not a human`() {
        // Nobody touched anything. Pausing here would stop the agent because a message
        // came in — the single most common event on a phone.
        assertFalse(
            PauseTrigger.isHumanSignal(
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                fromOwnApp = false,
            ),
        )
    }

    @Test
    fun `content refreshing under the agent is not a human`() {
        // Fires constantly — every list rebind, every loading spinner, every clock tick.
        assertFalse(
            PauseTrigger.isHumanSignal(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                fromOwnApp = false,
            ),
        )
    }

    @Test
    fun `a window change is not treated as a human`() {
        // Tempting — "the user switched apps" — but it also fires when AURA launches an
        // app, and a cold launch takes well over the self-action window. Including it
        // would make AURA pause itself on its own app launches, which is worse than
        // missing an app switch. Revisit only with a launch-aware suppression.
        assertFalse(
            PauseTrigger.isHumanSignal(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                fromOwnApp = false,
            ),
        )
    }

    @Test
    fun `AURA's own interface is never a human`() {
        // The overlay's pill animates and its buttons are real, clickable views. Without
        // this, AURA tapping its own UI reads as the user grabbing the phone.
        assertFalse(PauseTrigger.isHumanSignal(AccessibilityEvent.TYPE_VIEW_CLICKED, fromOwnApp = true))
    }
}
