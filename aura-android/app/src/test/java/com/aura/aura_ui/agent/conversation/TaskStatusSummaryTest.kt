package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.ledger.PlanStep
import com.aura.aura_ui.agent.ledger.PlanStepStatus
import com.aura.aura_ui.agent.ledger.RunLedger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStatusSummaryTest {

    private fun ledger(
        goal: String = "order my usual from Swiggy",
        planSteps: List<PlanStep> = emptyList(),
        facts: List<String> = emptyList(),
        deadEnds: List<String> = emptyList(),
        totalSteps: Int = 0,
        failedSteps: Int = 0,
    ) = RunLedger(
        runId = "r1", goal = goal, provider = "groq", modelId = "m", startedAtMs = 0L,
        planSteps = planSteps, facts = facts, deadEnds = deadEnds,
        totalSteps = totalSteps, failedSteps = failedSteps,
    )

    @Test fun `always names the goal`() {
        assertTrue(TaskStatusSummary.of(ledger()).contains("order my usual from Swiggy"))
    }

    @Test fun `reports plan progress as done over total`() {
        val summary = TaskStatusSummary.of(
            ledger(
                planSteps = listOf(
                    PlanStep("open Swiggy", PlanStepStatus.DONE),
                    PlanStep("find the restaurant", PlanStepStatus.DONE),
                    PlanStep("place the order", PlanStepStatus.IN_PROGRESS),
                ),
            ),
        )
        assertTrue(summary.contains("2 of 3"))
        assertTrue("the in-progress step is the useful one to say", summary.contains("Currently: place the order"))
    }

    @Test fun `falls back to the next pending step when nothing is in progress`() {
        val summary = TaskStatusSummary.of(
            ledger(
                planSteps = listOf(
                    PlanStep("open Swiggy", PlanStepStatus.DONE),
                    PlanStep("find the restaurant", PlanStepStatus.PENDING),
                ),
            ),
        )
        // "What's it doing?" must never be answered with silence.
        assertTrue(summary.contains("Currently: find the restaurant"))
    }

    @Test fun `an unplanned task still reports honest progress`() {
        // Short tasks skip set_plan entirely; the raw action count is what we have.
        assertTrue(TaskStatusSummary.of(ledger(totalSteps = 4)).contains("4 actions taken"))
    }

    @Test fun `a task that has done nothing yet says just started`() {
        assertTrue(TaskStatusSummary.of(ledger()).contains("just started"))
    }

    @Test fun `surfaces failures and the last dead end`() {
        val summary = TaskStatusSummary.of(
            ledger(totalSteps = 6, failedSteps = 2, deadEnds = listOf("old one", "the cart button did nothing")),
        )
        assertTrue(summary.contains("2 step(s) failed"))
        assertTrue("only the most recent dead end is worth saying", summary.contains("the cart button did nothing"))
        assertFalse(summary.contains("old one"))
    }

    @Test fun `keeps only the most recent few facts`() {
        val summary = TaskStatusSummary.of(ledger(facts = listOf("a", "b", "c", "d", "e")))
        assertTrue(summary.contains("c; d; e"))
        assertFalse("a status answer is not a transcript", summary.contains("a; b"))
    }

    @Test fun `tells the model to speak naturally and not to claim completion`() {
        val summary = TaskStatusSummary.of(ledger(totalSteps = 1))
        // Without this the model reads the field labels aloud like a status readout, and a
        // half-finished plan reads to it as a finished one.
        assertTrue(summary.contains("natural sentences"))
        assertTrue(summary.contains("do not say it is finished"))
    }
}
