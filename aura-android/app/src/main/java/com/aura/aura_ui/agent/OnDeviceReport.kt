package com.aura.aura_ui.agent

import com.aura.aura_ui.agent.ledger.PauseCheckpoint
import com.aura.aura_ui.agent.ledger.RunOutcome
import com.aura.aura_ui.agent.ledger.StepRecord
import com.aura.aura_ui.services.RemoteRun
import com.aura.aura_ui.services.RemoteRun.Outcome

/**
 * Builds the report card for a finished on-device run.
 *
 * - Contract: pure; every end reason maps to one [Outcome], and an unknown one reads as failed.
 * - Change together: the end reasons set in `AuraAgent.runSpike`.
 */
object OnDeviceReport {

    private const val CLIENT = "AURA"
    private const val MAX_RECENT = 6

    /**
     * @param verdict the harness's judgement, from `RunOutcome.verdict`; null when there was none.
     * @param claimed what the model said in `end_session`'s `outcome`.
     * @param reply what AURA said at the end, shown when the model wrote no report.
     * @param writtenReport the `report` argument of the accepted `end_session`.
     */
    fun of(
        endReason: String,
        verdict: String?,
        claimed: String?,
        reply: String?,
        writtenReport: String?,
        durationMs: Long,
        steps: Int,
        recentSteps: List<StepRecord>,
    ) = RemoteRun.Report(
        outcome = outcomeOf(endReason, verdict, claimed, steps),
        client = CLIENT,
        durationMs = durationMs,
        steps = steps,
        summary = null,
        markdown = writtenReport?.takeIf { it.isNotBlank() } ?: reply?.takeIf { it.isNotBlank() },
        recent = recentSteps.takeLast(MAX_RECENT).map(::describe),
        onDevice = true,
    )

    /** Why [verdict] outranks [claimed]: the two differ exactly when the model over-claimed. */
    internal fun outcomeOf(endReason: String, verdict: String?, claimed: String?, steps: Int): Outcome =
        when (endReason) {
            PauseCheckpoint.COMPLETED -> when (verdict) {
                RunOutcome.SUCCESS -> Outcome.DONE
                RunOutcome.PARTIAL -> Outcome.PARTIAL
                RunOutcome.FAIL -> Outcome.FAILED
                else -> outcomeOfClaim(claimed)
            }
            "cancelled" -> Outcome.STOPPED
            PauseCheckpoint.PAUSED -> Outcome.PAUSED
            "budget", "iterations", "quota" -> if (steps > 0) Outcome.PARTIAL else Outcome.FAILED
            else -> Outcome.FAILED
        }

    private fun outcomeOfClaim(claimed: String?) = when (claimed) {
        "partial" -> Outcome.PARTIAL
        "failure" -> Outcome.FAILED
        else -> Outcome.DONE
    }

    private fun describe(step: StepRecord): String {
        val action = listOfNotNull(step.tool.replace('_', ' '), step.label?.takeIf { it.isNotBlank() })
            .joinToString(" ")
        return if (step.ok) action else "$action (failed)"
    }
}
