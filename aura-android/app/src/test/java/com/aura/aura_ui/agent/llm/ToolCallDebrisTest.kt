package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driven by what the device actually said.
 *
 * Every string in the first block is copied verbatim from a 2026-08-26 eval run's `outcome`
 * field — these are not invented shapes, they are four things AURA read out loud. Three of the
 * four runs scored PASS, because the suite checks the screen and never the utterance.
 */
class ToolCallDebrisTest {

    // ── the four real ones ───────────────────────────────────────────────────

    /** Task 7, session 1787707753028. The answer is inside the fake call's `reason`. */
    @Test
    fun `task 7 - a script-wrapped end_session yields its reason`() {
        val spoken = "<script>end_session(goal_type='open_app',outcome='success'," +
            "reason='Opened the Clock app successfully.')</script>"
        assertEquals("Opened the Clock app successfully.", ToolCallDebris.clean(spoken))
    }

    /** Task 9, session 1787708541416. Debris first, then the real sentence. */
    @Test
    fun `task 9 - key-value debris is dropped and the trailing sentence survives`() {
        val spoken = "uniqueItems: [\"com.aura.aura_ui.feature.debug\"]" +
            "package: \"com.aura.aura_ui.feature.debug\"goal_type: \"other\"outcome: \"success\"" +
            "reason: \"The current foreground app is the AURA UI debug package.\"* \n" +
            "The current foreground app is AURA (`com.aura.aura_ui.feature.debug`)."
        val cleaned = ToolCallDebris.clean(spoken)
        assertTrue("said: $cleaned", cleaned.contains("The current foreground app is AURA"))
        assertFalse("said: $cleaned", cleaned.contains("uniqueItems"))
        assertFalse("said: $cleaned", cleaned.contains("goal_type"))
    }

    /** Task 10, session 1787708585625. `prefer_text` is the model's own "say this". */
    @Test
    fun `task 10 - prefer_text wins over the fake end_session beside it`() {
        val spoken = "<prefer_text>The screen shows the status bar and notifications with the " +
            "time set to 7:13, a 91% battery level.</prefer_text>" +
            "<end_session R=\"The screen shows the status bar and notifications.\" " +
            "goal_type=\"other\" outcome=\"success\"/>"
        assertEquals(
            "The screen shows the status bar and notifications with the time set to 7:13, " +
                "a 91% battery level.",
            ToolCallDebris.clean(spoken),
        )
    }

    /**
     * Task 4, session 1787708451124 — the run that died on MALFORMED_FUNCTION_CALL. Nothing here
     * was ever meant for a person, so the honest result is nothing, and the caller's
     * "no final answer" path takes over rather than this inventing a reply.
     */
    @Test
    fun `task 4 - pure machinery yields nothing to say`() {
        val spoken = "<custom_instruction>Always act on the latest perception. Tap som_id 110 " +
            "(Attach icon).</custom_instruction><call>default_api:mark_step{index:1," +
            "note:Opened chat with Amma in WhatsApp,status:done}</call>" +
            "<call>default_api:tap{som_id:110}</call>"
        assertEquals("", ToolCallDebris.clean(spoken))
    }

    // ── the guarantee ────────────────────────────────────────────────────────

    /** The one promise this makes: tool-call syntax is never spoken. */
    @Test
    fun `no output ever carries tool-call syntax`() {
        listOf(
            "<script>end_session(reason='done')</script>",
            "<call>default_api:tap{som_id:5}</call>",
            "<end_session R=\"finished\" outcome=\"success\"/>",
            "goal_type: \"other\" outcome: \"success\"",
            "<prefer_text>All set, the alarm is on.</prefer_text><end_session outcome=\"success\"/>",
        ).forEach { assertFalse("leaked from: $it", ToolCallDebris.hasDebris(ToolCallDebris.clean(it))) }
    }

    // ── the common path must be untouched ────────────────────────────────────

    @Test
    fun `an ordinary reply passes through unchanged`() {
        listOf(
            "I've set an alarm for 7:00 AM.",
            "The tallest building in the world is the Burj Khalifa, at 828 metres.",
            "Your next alarm is set for 13 hours and 53 minutes from now.",
            "Opened YouTube and searched for lofi music.",
        ).forEach { assertEquals(it, ToolCallDebris.clean(it)) }
    }

    /** Talking *about* the tools is not the same as emitting one. */
    @Test
    fun `prose that merely mentions a tool is not debris`() {
        val reply = "I could not tap that button, so I stopped rather than guess."
        assertEquals(reply, ToolCallDebris.clean(reply))
    }

    @Test
    fun `empty and blank input stay empty`() {
        assertEquals("", ToolCallDebris.clean(null))
        assertEquals("", ToolCallDebris.clean("   "))
    }

    /**
     * A couple of orphaned words left over from stripping are machinery residue, not a reply.
     * Speaking "success" is the same failure in a smaller font.
     */
    @Test
    fun `residue too short to be an answer yields nothing`() {
        assertEquals("", ToolCallDebris.clean("<call>default_api:tap{som_id:5}</call> success"))
    }
}
