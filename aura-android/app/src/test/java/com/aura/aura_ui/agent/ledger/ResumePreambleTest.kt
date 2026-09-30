package com.aura.aura_ui.agent.ledger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePreambleTest {
    private val seed = RunLedger(
        runId = "r",
        goal = "book a cab home",
        provider = "GROQ",
        modelId = "m",
        startedAtMs = 0L,
        planSteps = listOf(
            PlanStep("open the ride app", PlanStepStatus.DONE),
            PlanStep("pick the ride", PlanStepStatus.IN_PROGRESS),
        ),
        facts = listOf("home saved as a favourite"),
    )

    @Test fun `resume input leads with the preamble, not the ledger marker`() {
        val text = ResumePreamble.forResume(seed)
        // Must NOT start with the ledger marker, or the per-turn injector would drop this whole
        // message (goal + preamble included) as a stale ledger block on the next turn.
        assertFalse(text.startsWith(RunLedgerRenderer.MARKER))
        assertTrue(text.startsWith("You are resuming"))
    }

    @Test fun `resume input embeds the rendered ledger so turn 1 sees the carried plan`() {
        val text = ResumePreamble.forResume(seed)
        assertTrue("carries the ledger block", text.contains(RunLedgerRenderer.MARKER))
        assertTrue("carries the in-progress step", text.contains("pick the ride"))
        assertTrue("carries a noted fact", text.contains("home saved as a favourite"))
    }
}
