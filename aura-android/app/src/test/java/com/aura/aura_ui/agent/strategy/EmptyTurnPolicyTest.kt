package com.aura.aura_ui.agent.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device failure this guards against: Gemini 3.x (esp. Flash-Lite) sometimes emits a turn
 * that is ONLY a `<thought>` block — reasoning about the next action, but no tool call and no
 * answer. The stock loop treats any text turn as "done" and finishes, abandoning the task
 * mid-flight. [EmptyTurnPolicy.isThinkingOnly] identifies these so the loop can nudge instead.
 */
class EmptyTurnPolicyTest {

    // Verbatim from the real stalled run (Battery-settings task, gemini-3.1-flash-lite).
    private val realThinkingOnlyTurn =
        "<thought>**Navigating the Settings Menu**\n\nAlright, I've finished the initial step, and " +
            "now it's time to tackle step two: locating the \"Battery\" option within the Settings " +
            "menu. My immediate instinct was to *perceive* the screen, but then I realized I should " +
            "just use `scroll_to` for \"Battery\". Much better approach.\n\n\n</thought>"

    @Test fun `a pure thought turn is thinking-only`() {
        assertTrue(EmptyTurnPolicy.isThinkingOnly(realThinkingOnlyTurn))
    }

    @Test fun `a bare think block is thinking-only`() {
        assertTrue(EmptyTurnPolicy.isThinkingOnly("<think>let me consider the options</think>"))
    }

    @Test fun `blank or whitespace is thinking-only`() {
        assertTrue(EmptyTurnPolicy.isThinkingOnly(""))
        assertTrue(EmptyTurnPolicy.isThinkingOnly("   \n  "))
    }

    @Test fun `a real final answer is NOT thinking-only`() {
        assertFalse(EmptyTurnPolicy.isThinkingOnly("The Settings app is open and Battery is now shown."))
    }

    @Test fun `reasoning followed by a real answer is NOT thinking-only`() {
        assertFalse(
            EmptyTurnPolicy.isThinkingOnly(
                "<thought>I should confirm the screen</thought>\nDone — Battery settings are open.",
            ),
        )
    }

    @Test fun `the nudge cap is a small positive bound`() {
        assertTrue(EmptyTurnPolicy.MAX_NUDGES in 1..3)
    }

    @Test fun `nudge budget grants exactly max nudges then refuses`() {
        val b = NudgeBudget(max = 2)
        assertTrue(b.tryConsume())
        assertTrue(b.tryConsume())
        assertFalse("third nudge must be refused", b.tryConsume())
        assertEquals(2, b.used)
    }

    @Test fun `a zero budget never nudges`() {
        val b = NudgeBudget(max = 0)
        assertFalse(b.tryConsume())
        assertEquals(0, b.used)
    }
}
