package com.aura.aura_ui.agent.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The store is the one place that knows what the phone is doing. It is fed by events from several
 * threads — accessibility callbacks, the tool coroutines, the notification listener — so each
 * update must touch only its own field and never clobber a neighbour.
 */
class AuraStateStoreTest {

    @Before fun clean() = AuraStateStore.reset()

    /** Breaks if the store stops starting empty: a fresh session would inherit the previous one's
     *  screen and task, and AURA would open by referring to something long gone. */
    @Test fun `a reset store knows nothing`() {
        assertEquals(AuraStateSnapshot(), AuraStateStore.current())
    }

    /** Breaks if one update clobbers another — the failure mode of "just replace the snapshot". */
    @Test fun `each signal updates only its own field`() {
        AuraStateStore.onForegroundApp("WhatsApp", "Chat with Sarah")
        AuraStateStore.onNotifications(listOf(NotificationBrief("Gmail", "Bill")))
        AuraStateStore.onDeviceSignals(batteryPercent = 42, isCharging = true, nowPlaying = null, dndOn = false)

        val s = AuraStateStore.current()
        assertEquals("WhatsApp", s.foregroundApp)
        assertEquals("Chat with Sarah", s.screenName)
        assertEquals(listOf(NotificationBrief("Gmail", "Bill")), s.notifications)
        assertEquals(42, s.batteryPercent)
    }

    /** Breaks if a task's progress replaces its label, or vice versa — the brain lane would lose
     *  either what the task is or where it has got to. */
    @Test fun `task progress accumulates onto the running task`() {
        AuraStateStore.onTaskStarted("order a coffee")
        AuraStateStore.onTaskProgress("on the checkout page")
        assertEquals(RunningTask("order a coffee", "on the checkout page"), AuraStateStore.current().runningTask)
    }

    /** Breaks if progress can invent a task. A progress line arriving after the task ended would
     *  otherwise resurrect it and AURA would report work that is not happening. */
    @Test fun `progress with no running task is ignored`() {
        AuraStateStore.onTaskProgress("on the checkout page")
        assertNull(AuraStateStore.current().runningTask)
    }

    /** Breaks if a finished task lingers: the brain lane would keep telling the user their task is
     *  still running after it landed. */
    @Test fun `finishing a task clears it but leaves the rest of the state alone`() {
        AuraStateStore.onForegroundApp("Chrome", null)
        AuraStateStore.onTaskStarted("order a coffee")
        AuraStateStore.onTaskFinished()

        val s = AuraStateStore.current()
        assertNull(s.runningTask)
        assertEquals("Chrome", s.foregroundApp)
    }

    /** Breaks if the agent's last action stops being recorded — follow-ups like "why did it do
     *  that" lose their referent. */
    @Test fun `the last agent action is kept`() {
        AuraStateStore.onAgentAction("tapped \"Place order\"")
        assertEquals("tapped \"Place order\"", AuraStateStore.current().lastAgentAction)
    }

    /** Breaks if closing the browser leaves a stale page behind, so the brain lane believes a
     *  window is open on a page the user closed minutes ago. */
    @Test fun `closing the browser clears the page`() {
        AuraStateStore.onBrowserPage("github.com — Pull requests")
        assertEquals("github.com — Pull requests", AuraStateStore.current().browserPage)
        AuraStateStore.onBrowserPage(null)
        assertNull(AuraStateStore.current().browserPage)
    }

    /** Breaks if the store stops being readable as a rendered block — this is the whole handoff to
     *  the brain lane, and an integration slip here silently un-does every other piece. */
    @Test fun `the store renders through to a brain-lane block`() {
        AuraStateStore.onForegroundApp("Spotify", null)
        assertEquals("They are in Spotify.", AuraStateStore.current().renderForBrainLane())
    }
}
