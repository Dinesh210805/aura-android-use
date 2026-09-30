package com.aura.aura_ui.agent.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeOfferTest {
    private fun ledger(
        runId: String = "r1",
        goal: String = "add AirPods to my Amazon cart",
        planSteps: List<PlanStep> = emptyList(),
        totalSteps: Int = 0,
    ) = RunLedger(
        runId = runId,
        goal = goal,
        provider = "GROQ",
        modelId = "llama",
        startedAtMs = 1_000_000L,
        planSteps = planSteps,
        totalSteps = totalSteps,
    )

    @Test fun `no ledger yields no offer`() {
        assertNull(ResumeOffer.from(null))
    }

    @Test fun `offer carries ledger id and goal`() {
        val offer = ResumeOffer.from(ledger(runId = "abc"))!!
        assertEquals("abc", offer.ledgerId)
        assertEquals("add AirPods to my Amazon cart", offer.goal)
    }

    @Test fun `progress counts done steps against the plan size`() {
        val offer = ResumeOffer.from(
            ledger(
                planSteps = listOf(
                    PlanStep("open Amazon", PlanStepStatus.DONE),
                    PlanStep("search AirPods", PlanStepStatus.DONE),
                    PlanStep("add to cart", PlanStepStatus.IN_PROGRESS),
                ),
            ),
        )!!
        assertEquals(2, offer.stepsDone)
        assertEquals(3, offer.stepsTotal)
    }

    @Test fun `chip label reads Resume goal step k of n when a plan exists`() {
        val offer = ResumeOffer.from(
            ledger(
                goal = "book a cab",
                planSteps = listOf(PlanStep("a", PlanStepStatus.DONE), PlanStep("b")),
            ),
        )!!
        assertEquals("Resume: book a cab (step 1/2)", offer.chipLabel())
    }

    @Test fun `chip label omits the step counter when there is no plan`() {
        val offer = ResumeOffer.from(ledger(goal = "turn on wifi", totalSteps = 4))!!
        assertEquals("Resume: turn on wifi", offer.chipLabel())
    }

    @Test fun `voice prompt asks the user to continue the unfinished task`() {
        val offer = ResumeOffer.from(ledger(goal = "send mom a message"))!!
        assertEquals(
            "You have an unfinished task: send mom a message. Want me to continue it?",
            offer.voicePrompt(),
        )
    }

    @Test fun `a long goal is truncated in the chip label`() {
        val long = "a".repeat(120)
        val label = ResumeOffer.from(ledger(goal = long))!!.chipLabel()
        assert(label.length <= "Resume: ".length + ResumeOffer.MAX_GOAL_CHARS + 1) {
            "chip label must be bounded, was ${label.length}"
        }
        assert(label.endsWith("…")) { "truncated goal must be elided" }
    }
}
