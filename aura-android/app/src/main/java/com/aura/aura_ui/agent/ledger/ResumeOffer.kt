package com.aura.aura_ui.agent.ledger

/**
 * A resumable run, formatted for the two surfaces that offer it (user decision: never auto-resume).
 *  - the agent chat chip ([chipLabel])
 *  - the Gemini Live voice ask ([voicePrompt])
 *
 * Pure view over [RunLedger] so both surfaces render the offer identically; build one with
 * [ResumeOffer.from] from `RunLedgerStore.latestResumable()`.
 */
data class ResumeOffer(
    val ledgerId: String,
    val goal: String,
    val stepsDone: Int,
    val stepsTotal: Int,
) {
    fun chipLabel(): String {
        val g = goal.trim().let { if (it.length > MAX_GOAL_CHARS) it.take(MAX_GOAL_CHARS).trimEnd() + "…" else it }
        return if (stepsTotal > 0) "Resume: $g (step $stepsDone/$stepsTotal)" else "Resume: $g"
    }

    fun voicePrompt(): String = "You have an unfinished task: ${goal.trim()}. Want me to continue it?"

    companion object {
        const val MAX_GOAL_CHARS = 60

        fun from(ledger: RunLedger?): ResumeOffer? {
            if (ledger == null) return null
            return ResumeOffer(
                ledgerId = ledger.runId,
                goal = ledger.goal,
                stepsDone = ledger.planSteps.count { it.status == PlanStepStatus.DONE },
                stepsTotal = ledger.planSteps.size,
            )
        }
    }
}
