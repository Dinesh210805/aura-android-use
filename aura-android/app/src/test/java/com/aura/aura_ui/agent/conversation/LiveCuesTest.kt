package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cues are instructions the model renders in its own words, so these tests assert PROPERTIES the
 * wording must have — never the exact sentence, which is a prompt-tuning decision and would make
 * every reword a test failure.
 */
class LiveCuesTest {

    /** Breaks if the label stops reaching the cue: AURA would announce "starting that" with no idea
     *  what "that" is. */
    @Test fun `the starting cue carries the task label and is spoken immediately`() {
        val cue = LiveCues.taskStarting("order a coffee")
        assertTrue(cue.text.contains("order a coffee"))
        assertEquals(CueDelivery.SPEAK_NOW, cue.delivery)
    }

    /** Breaks if the starting cue stops warning against claiming completion — the exact failure that
     *  makes an assistant say "done!" the instant it begins. */
    @Test fun `the starting cue forbids claiming the task is finished`() {
        val text = LiveCues.taskStarting("order a coffee").text.lowercase()
        assertTrue(text.contains("not") && (text.contains("done") || text.contains("finished")))
    }

    /** Breaks if the standing-by cue starts speaking on its own. It rides the reply the user just
     *  triggered by speaking; a second spoken turn on top would talk over AURA's own answer. */
    @Test fun `the standing-by cue rides the current reply instead of speaking on its own`() {
        assertEquals(CueDelivery.CONTEXT_ONLY, LiveCues.taskOngoing().delivery)
    }

    /** Breaks if the standing-by cue stops offering the two controls the user actually has. That
     *  offer is the whole reason this beat exists. */
    @Test fun `the standing-by cue offers both stopping and changing the task`() {
        val text = LiveCues.taskOngoing().text.lowercase()
        assertTrue(text.contains("stop"))
        assertTrue(text.contains("change") || text.contains("update"))
    }

    /** Breaks if the outcome stops reaching the finish cue — AURA would announce a result it does
     *  not have, which reads as invention. */
    @Test fun `the finish cue carries the outcome and is spoken immediately`() {
        val cue = LiveCues.taskFinished("the order went through")
        assertTrue(cue.text.contains("the order went through"))
        assertEquals(CueDelivery.SPEAK_NOW, cue.delivery)
    }

    // ── the opening cue ──────────────────────────────────────────────────────

    /** Breaks if a quiet phone starts producing a status report. The user's explicit choice: lead
     *  with something only when there IS something. */
    @Test fun `an opening with nothing notable asks for a plain greeting`() {
        val cue = LiveCues.opening(emptyList())
        val text = cue.text.lowercase()
        assertFalse(text.contains("battery"))
        assertFalse(text.contains("unread"))
        assertEquals(CueDelivery.SPEAK_NOW, cue.delivery)
    }

    /** Breaks if notable facts stop reaching the opener — the entire "intelligent greeting" feature. */
    @Test fun `an opening with notable facts carries every one of them`() {
        val cue = LiveCues.opening(listOf("2 unread: WhatsApp (Sarah, Mom)", "battery is at 9%"))
        assertTrue(cue.text.contains("2 unread: WhatsApp (Sarah, Mom)"))
        assertTrue(cue.text.contains("battery is at 9%"))
    }

    /** Breaks if the opener stops telling the model to lead with the most important fact — a greeting
     *  that opens with battery while two people are waiting on a reply is the wrong instinct. */
    @Test fun `an opening tells the model to lead with the first fact`() {
        val text = LiveCues.opening(listOf("an unfinished task: ordering a coffee")).text.lowercase()
        assertTrue(text.contains("first") || text.contains("lead") || text.contains("most important"))
    }

    /** Breaks if the opener stops constraining length: an unbounded greeting recites the whole list
     *  and lands as a briefing rather than a hello. */
    @Test fun `an opening keeps the greeting short`() {
        val text = LiveCues.opening(listOf("a", "b", "c")).text.lowercase()
        assertTrue(text.contains("one sentence") || text.contains("short") || text.contains("brief"))
    }
}
