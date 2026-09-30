package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.ledger.PlanStepStatus
import com.aura.aura_ui.agent.ledger.RunLedger

/**
 * Answers "what is the phone task actually doing right now?" for the conversation plane.
 *
 * Kept as an interface so [CompanionTools] stays pure and testable: the real implementation reads
 * encrypted storage and needs an Android `Context`, which a unit test should never have to build.
 */
fun interface TaskStatusProvider {
    /**
     * A short, speakable summary of the task in flight, or null when there is nothing to report.
     *
     * This used to take a `taskStartedAtMs` boundary, because the only way to find the running
     * ledger was to scan the encrypted store for the newest un-finalized one — and "un-finalized"
     * is ALSO the app-death signal, so a run that died hours ago satisfied the same predicate. The
     * boundary existed to stop AURA narrating a dead task's progress.
     *
     * `ActiveRunRegistry` made the boundary unnecessary: the live controller of the executing run
     * is now directly reachable, so there is nothing to disambiguate and no debounced disk write to
     * race. The parameter was removed rather than left unused, so nobody reimplements the heuristic
     * from its presence.
     */
    suspend fun currentStatus(): String?
}

/**
 * Renders a [RunLedger] as something AURA can say out loud.
 *
 * Deliberately NOT `RunLedgerRenderer`: that one writes a dense block for a *model driving a phone*
 * — every executed step, dead end and fact, up to a couple of thousand characters. Reading it aloud
 * would be unbearable, and feeding it to the conversation model invites it to recite mechanics the
 * persona explicitly forbids. This is the spoken-answer view: the goal, how far through the plan it
 * is, what it is on now, and anything that has gone wrong.
 */
object TaskStatusSummary {

    fun of(ledger: RunLedger): String {
        val parts = mutableListOf<String>()
        parts += "Task: ${ledger.goal}"

        if (ledger.planSteps.isNotEmpty()) {
            val done = ledger.planSteps.count { it.status == PlanStepStatus.DONE }
            parts += "Progress: $done of ${ledger.planSteps.size} steps done"
            // The step actually in hand is the single most useful thing to say; fall back to the
            // next pending one so "what's it doing?" is never answered with silence.
            val current = ledger.planSteps.firstOrNull { it.status == PlanStepStatus.IN_PROGRESS }
                ?: ledger.planSteps.firstOrNull { it.status == PlanStepStatus.PENDING }
            current?.let { parts += "Currently: ${it.text}" }
        } else if (ledger.totalSteps > 0) {
            // No plan means a short task that skipped planning — the raw count is still honest.
            parts += "Progress: ${ledger.totalSteps} actions taken so far"
        } else {
            parts += "Progress: just started"
        }

        if (ledger.failedSteps > 0) {
            parts += "${ledger.failedSteps} step(s) failed and were retried"
        }
        ledger.facts.takeLast(MAX_FACTS).takeIf { it.isNotEmpty() }?.let {
            parts += "Found so far: ${it.joinToString("; ")}"
        }
        ledger.deadEnds.lastOrNull()?.let { parts += "Last thing that didn't work: $it" }

        // The model narrates this in its own voice; the trailing instruction keeps it from reading
        // the field labels above out loud like a status readout.
        parts += "Tell the user this in one or two natural sentences. It is still running — do " +
            "not say it is finished."
        return parts.joinToString("\n")
    }

    /** Enough for "it found the restaurant and picked your usual", not a transcript. */
    private const val MAX_FACTS = 3
}
