package com.aura.aura_ui.agent.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot is what finally lets the brain lane reason about the phone instead of guessing.
 * Both functions here are pure, so the whole contract is testable with no device and no key.
 */
class AuraStateSnapshotTest {

    // ── renderForBrainLane: what the reasoning half is told ──────────────────

    /** Breaks if an empty snapshot starts emitting a block: the prompt would carry a "here is the
     *  phone state" heading with nothing under it, which reads to the model as "nothing is happening"
     *  stated authoritatively rather than "we don't know". */
    @Test fun `an empty snapshot renders nothing at all`() {
        assertNull(AuraStateSnapshot().renderForBrainLane())
    }

    /** Breaks if the foreground app stops reaching the prompt — the single most useful fact, and the
     *  one that is hardcoded to null in production today. */
    @Test fun `the foreground app and screen are rendered together`() {
        val s = AuraStateSnapshot(foregroundApp = "WhatsApp", screenName = "Chat with Sarah")
        assertEquals("They are in WhatsApp, on \"Chat with Sarah\".", s.renderForBrainLane())
    }

    /** Breaks if a missing screen name starts printing an empty quote or the word null. */
    @Test fun `an app with no screen name renders on its own`() {
        val s = AuraStateSnapshot(foregroundApp = "Spotify")
        assertEquals("They are in Spotify.", s.renderForBrainLane())
    }

    /** Breaks if a running task stops being surfaced: the brain lane would offer to start work that
     *  is already underway. */
    @Test fun `a running task is rendered with its latest progress`() {
        val s = AuraStateSnapshot(
            runningTask = RunningTask(label = "order a coffee", lastProgress = "on the checkout page"),
        )
        assertEquals(
            "A phone task is running right now: order a coffee (on the checkout page).",
            s.renderForBrainLane(),
        )
    }

    /** Breaks if progress-less tasks render a dangling empty bracket. */
    @Test fun `a running task with no progress yet omits the bracket`() {
        val s = AuraStateSnapshot(runningTask = RunningTask("order a coffee", null))
        assertEquals("A phone task is running right now: order a coffee.", s.renderForBrainLane())
    }

    /** Breaks if notification bodies ever start being rendered. The user's explicit choice is app +
     *  sender only, so message text never reaches Google. */
    @Test fun `notifications render as app and sender only`() {
        val s = AuraStateSnapshot(
            notifications = listOf(
                NotificationBrief("WhatsApp", "Sarah"),
                NotificationBrief("WhatsApp", "Mom"),
                NotificationBrief("Gmail", null),
            ),
        )
        assertEquals(
            "Unread: WhatsApp (Sarah, Mom), Gmail.",
            s.renderForBrainLane(),
        )
    }

    /** Breaks if the browser page stops being surfaced — the brain lane would not know a co-pilot
     *  window is already open on the very page being discussed. */
    @Test fun `an open browser page is rendered`() {
        val s = AuraStateSnapshot(browserPage = "github.com — Pull requests")
        assertEquals("A browser window is open on github.com — Pull requests.", s.renderForBrainLane())
    }

    /** Breaks if the agent's last action stops being carried: follow-ups like "why did that fail"
     *  lose their referent. */
    @Test fun `the agent's last action is rendered`() {
        val s = AuraStateSnapshot(lastAgentAction = "tapped \"Place order\"")
        assertEquals("The last thing the agent did: tapped \"Place order\".", s.renderForBrainLane())
    }

    /** Breaks if the ordering changes. Screen first, then task, is deliberate: the model reads the
     *  top of a block most reliably, and where the user is matters more than anything else. */
    @Test fun `a full snapshot renders every line in a stable order`() {
        val s = AuraStateSnapshot(
            foregroundApp = "Chrome",
            screenName = "Inbox",
            browserPage = "mail.google.com — Inbox",
            runningTask = RunningTask("clear my inbox", "archiving"),
            lastAgentAction = "tapped Archive",
            notifications = listOf(NotificationBrief("Slack", "Dev")),
            batteryPercent = 14,
            isCharging = false,
            nowPlaying = "Miles Davis",
            dndOn = true,
        )
        assertEquals(
            listOf(
                "They are in Chrome, on \"Inbox\".",
                "A browser window is open on mail.google.com — Inbox.",
                "A phone task is running right now: clear my inbox (archiving).",
                "The last thing the agent did: tapped Archive.",
                "Unread: Slack (Dev).",
                "Battery 14%, not charging.",
                "Playing: Miles Davis.",
                "Do not disturb is on.",
            ).joinToString("\n"),
            s.renderForBrainLane(),
        )
    }

    /** Breaks if charging state is dropped — "battery 14%" alone would have AURA fussing about a
     *  phone that is already on the charger. */
    @Test fun `a charging battery says so`() {
        val s = AuraStateSnapshot(batteryPercent = 14, isCharging = true)
        assertEquals("Battery 14%, charging.", s.renderForBrainLane())
    }

    // ── notableFacts: what is worth opening a conversation with ──────────────

    /** Breaks if a quiet phone starts producing an opener. The user's explicit choice: say something
     *  only when there IS something, otherwise a plain hello. */
    @Test fun `a quiet phone has nothing notable`() {
        val s = AuraStateSnapshot(foregroundApp = "Settings", batteryPercent = 80)
        assertTrue(s.notableFacts().isEmpty())
    }

    /** Breaks if unread messages stop being notable — the main thing worth opening with. */
    @Test fun `unread notifications are notable`() {
        val s = AuraStateSnapshot(
            notifications = listOf(NotificationBrief("WhatsApp", "Sarah"), NotificationBrief("WhatsApp", "Mom")),
        )
        assertEquals(listOf("2 unread: WhatsApp (Sarah, Mom)"), s.notableFacts())
    }

    /** Breaks if the low-battery threshold moves or charging stops suppressing it. */
    @Test fun `a low battery is notable only when not charging`() {
        assertEquals(listOf("battery is at 14%"), AuraStateSnapshot(batteryPercent = 14).notableFacts())
        assertTrue(AuraStateSnapshot(batteryPercent = 14, isCharging = true).notableFacts().isEmpty())
        assertTrue(AuraStateSnapshot(batteryPercent = 21).notableFacts().isEmpty())
    }

    /** Breaks if an unfinished task stops being notable: the one thing a user most wants raised the
     *  moment they open the assistant again. */
    @Test fun `an unfinished task is notable`() {
        val s = AuraStateSnapshot(unfinishedTask = "ordering a coffee")
        assertEquals(listOf("an unfinished task: ordering a coffee"), s.notableFacts())
    }

    /** Breaks if the ordering changes — an unfinished task must outrank chatter about battery. */
    @Test fun `notable facts come back most-important first`() {
        val s = AuraStateSnapshot(
            unfinishedTask = "ordering a coffee",
            notifications = listOf(NotificationBrief("Slack", null)),
            batteryPercent = 9,
        )
        assertEquals(
            listOf("an unfinished task: ordering a coffee", "1 unread: Slack", "battery is at 9%"),
            s.notableFacts(),
        )
    }
}
