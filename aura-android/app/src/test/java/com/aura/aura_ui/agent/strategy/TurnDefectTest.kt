package com.aura.aura_ui.agent.strategy

import com.aura.aura_ui.agent.llm.ToolCallDebris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second way a turn can end a run without doing anything: the model writes its tool call as
 * **text** instead of calling it.
 *
 * Every debris string below is verbatim from a 2026-08-26 eval run. Task 4's run additionally
 * came back with `finishReason: MALFORMED_FUNCTION_CALL` and simply stopped — no repair, no
 * retry. [ToolCallDebris] cleans the utterance at the TTS seam; this decides, one step earlier,
 * that the turn should never have been treated as the run's answer in the first place.
 */
class TurnDefectTest {

    // ── the one run that actually died ───────────────────────────────────────

    /**
     * Task 4, session 1787708451124 — the run that ended on `MALFORMED_FUNCTION_CALL`.
     *
     * Pure machinery: nothing here was ever meant for a person, so finishing on it abandons the
     * task AND says nothing. This is the case worth a turn to re-ask.
     */
    @Test
    fun `task 4 machinery with nothing salvageable is a text-shaped tool call`() {
        val text = "<custom_instruction>Always act on the latest perception. Tap som_id 110 " +
            "(Attach icon).</custom_instruction><call>default_api:mark_step{index:1," +
            "note:Opened chat with Amma in WhatsApp,status:done}</call>" +
            "<call>default_api:tap{som_id:110}</call>"
        assertEquals(TurnDefect.TEXT_SHAPED_TOOL_CALL, TurnDefect.of(hasToolCall = false, assistantText = text))
    }

    // ── debris that still carries the answer must NOT be re-requested ────────

    /**
     * Tasks 7, 9 and 10 all scored PASS: the world changed correctly and the sentence the user
     * needed was sitting inside the debris, where [ToolCallDebris.clean] finds it. Re-requesting
     * these spends a turn re-deriving an answer already in hand — and on a mutating task, invites
     * the model to repeat work it has already done.
     */
    @Test
    fun `task 7 keeps its salvageable reason instead of being re-requested`() {
        val text = "<script>end_session(goal_type='open_app',outcome='success'," +
            "reason='Opened the Clock app successfully.')</script>"
        assertNull(TurnDefect.of(hasToolCall = false, assistantText = text))
    }

    @Test
    fun `task 9 keeps its trailing sentence instead of being re-requested`() {
        val text = "uniqueItems: [\"com.aura.aura_ui.feature.debug\"]goal_type: \"other\"" +
            "outcome: \"success\"* \nThe current foreground app is AURA."
        assertNull(TurnDefect.of(hasToolCall = false, assistantText = text))
    }

    @Test
    fun `task 10 keeps its prefer_text instead of being re-requested`() {
        val text = "<prefer_text>The screen shows the status bar and notifications with the " +
            "time set to 7:13, a 91% battery level.</prefer_text>" +
            "<end_session R=\"The screen shows the status bar.\" outcome=\"success\"/>"
        assertNull(TurnDefect.of(hasToolCall = false, assistantText = text))
    }

    /**
     * The false positive this narrowing exists to prevent.
     *
     * A final answer that *quotes* markup — a browser or `read_screen` result carrying `<script`,
     * a page whose text contains `outcome="` — trips [ToolCallDebris.hasDebris]. Nudging it would
     * push the model into a spurious tool call AFTER the mutating work was already done. Its
     * prose survives `clean`, so it must classify as healthy.
     */
    @Test
    fun `an answer that merely quotes markup is not a text-shaped tool call`() {
        listOf(
            "The page source starts with <script src=\"analytics.js\"> which is why it loaded slowly.",
            "I read the form and its hidden field says outcome=\"pending\", so the order is not confirmed yet.",
        ).forEach {
            assertNull("nudged a real answer that merely quoted markup: $it", TurnDefect.of(hasToolCall = false, assistantText = it))
        }
    }

    // ── a turn that actually called a tool is never defective ────────────────

    /**
     * The load-bearing guard. A real tool call is the loop working correctly; whatever prose rides
     * alongside it is commentary, and re-requesting would throw away work the model just did.
     */
    @Test
    fun `a turn that called a tool is never defective`() {
        assertNull(TurnDefect.of(hasToolCall = true, assistantText = ""))
        assertNull(TurnDefect.of(hasToolCall = true, assistantText = "<thought>tapping now</thought>"))
        assertNull(
            TurnDefect.of(
                hasToolCall = true,
                assistantText = "<call>default_api:tap{som_id:5}</call>",
            ),
        )
    }

    // ── the existing defect still classifies the same way ────────────────────

    @Test
    fun `a pure thought turn is still thinking-only`() {
        val text = "<thought>**Navigating the Settings Menu**\n\nI should use scroll_to " +
            "for \"Battery\". Much better approach.</thought>"
        assertEquals(TurnDefect.THINKING_ONLY, TurnDefect.of(hasToolCall = false, assistantText = text))
    }

    @Test
    fun `a blank turn is thinking-only`() {
        assertEquals(TurnDefect.THINKING_ONLY, TurnDefect.of(hasToolCall = false, assistantText = "   "))
    }

    // ── a real answer must finish the run, not be re-requested ───────────────

    @Test
    fun `an ordinary final answer is not defective`() {
        listOf(
            "I've set an alarm for 7:00 AM.",
            "The tallest building in the world is the Burj Khalifa, at 828 metres.",
            "I could not tap that button, so I stopped rather than guess.",
        ).forEach { assertNull("re-requested a real answer: $it", TurnDefect.of(hasToolCall = false, assistantText = it)) }
    }

    // ── the nudges ───────────────────────────────────────────────────────────

    /**
     * Each defect gets its own instruction. Sending the thinking-only nudge ("do not just think")
     * to a model that DID try to act — it just serialised the attempt wrong — describes a failure
     * that did not happen, and leaves the real one unnamed.
     */
    @Test
    fun `each defect carries a distinct non-blank nudge`() {
        TurnDefect.entries.forEach { assertTrue("blank nudge for $it", it.nudge.isNotBlank()) }
        assertNotEquals(TurnDefect.THINKING_ONLY.nudge, TurnDefect.TEXT_SHAPED_TOOL_CALL.nudge)
    }

    /** The nudge must name the real failure and demand the real fix, not merely scold. */
    @Test
    fun `the text-shaped nudge says no tool ran and asks for a real call`() {
        val nudge = TurnDefect.TEXT_SHAPED_TOOL_CALL.nudge
        assertTrue(nudge.contains("nothing ran", ignoreCase = true))
        assertTrue(nudge.contains("end_session"))
    }

    /**
     * A nudge is quoted into the prompt, so a nudge that itself carries tool-call syntax teaches
     * the model the shape we are trying to talk it out of. Asserted against the detector directly
     * rather than through [TurnDefect.of], which the nudge's own prose would satisfy anyway.
     */
    @Test
    fun `no nudge contains tool-call debris`() {
        TurnDefect.entries.forEach {
            assertFalse("nudge for $it is itself debris", ToolCallDebris.hasDebris(it.nudge))
        }
    }

    // ── budgets are per-defect ───────────────────────────────────────────────

    /**
     * Separate budgets, deliberately. A shared one would let an early thinking-only turn spend the
     * allowance a later malformed call needs — and would silently change how many nudges the
     * existing thinking-only path gets, which is a behaviour change this fix did not ask for.
     */
    @Test
    fun `spending one defect's budget leaves the other's intact`() {
        val budgets = NudgeBudgets()
        repeat(EmptyTurnPolicy.MAX_NUDGES) {
            assertTrue(budgets.tryConsume(TurnDefect.THINKING_ONLY))
        }
        assertFalse(budgets.tryConsume(TurnDefect.THINKING_ONLY))
        assertTrue("the other defect was drained too", budgets.tryConsume(TurnDefect.TEXT_SHAPED_TOOL_CALL))
    }

    @Test
    fun `each defect budget is bounded`() {
        val budgets = NudgeBudgets()
        TurnDefect.entries.forEach { defect ->
            repeat(EmptyTurnPolicy.MAX_NUDGES) { budgets.tryConsume(defect) }
            assertFalse("$defect nudges are unbounded", budgets.tryConsume(defect))
            assertEquals(EmptyTurnPolicy.MAX_NUDGES, budgets.used(defect))
        }
    }
}
