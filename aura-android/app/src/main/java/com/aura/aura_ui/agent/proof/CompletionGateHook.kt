package com.aura.aura_ui.agent.proof

import android.util.Log
import com.aura.aura_ui.agent.ledger.RunLedgerController
import com.aura.aura_ui.agent.ledger.RunOutcome
import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.PreToolDecision
import com.aura.aura_ui.agent.mcpbridge.hooks.PreToolHook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The completion contract at the one place a run can declare itself done.
 *
 * Sits in the pre-tool chain on `end_session` and applies, in cost order: the free count gate
 * ([CompletionGate]), then the judge ([CompletionJudge]) — so a run that is plainly short of proof
 * is sent back without spending a model call on establishing that.
 *
 * A refusal is a [PreToolDecision.Deny], which the chain returns to the model as an error
 * tool-result. That matters more than the refusal itself: the agent keeps working from it, where a
 * verdict computed after the run could only have reported the shortfall too late to fix. Both
 * layers are capped ([CompletionGate.MAX_REFUSALS]) so the run always terminates.
 *
 * An honest `failure` or `partial` is never refused or judged — it costs nothing to believe, and a
 * gate that argued with an admission of failure would teach the model to stop admitting it.
 */
class CompletionGateHook(
    private val controller: RunLedgerController,
    private val judge: CompletionJudge,
    private val observed: ObservedText,
    /** Checks a no-plan run's success claim against the screen; null skips that check. */
    private val stepChecker: StepChecker? = null,
) : PreToolHook {

    private var refusals = 0

    /** Whatever verdict this run reached, for the reply the user hears. Null = never ended here. */
    @Volatile
    var outcome: RunOutcome? = null
        private set

    override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext): PreToolDecision {
        if (toolName != END_SESSION) return PreToolDecision.Proceed
        val claimed = args["outcome"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()
        val answer = args["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val ledger = controller.snapshot()

        if (claimed != RunOutcome.SUCCESS) {
            // Take the admission at face value and record it, so the reply and the eval both see
            // what the model actually said instead of the default "completed". A call with no
            // outcome argument claims nothing, so nothing is confirmed either.
            record(
                RunOutcome(
                    claimed = claimed,
                    verdict = when (claimed) {
                        RunOutcome.PARTIAL -> RunOutcome.PARTIAL
                        null -> RunOutcome.UNVERIFIED
                        else -> RunOutcome.FAIL
                    },
                    why = answer.takeIf { claimed != null && it.isNotBlank() },
                    proven = ledger.findings.size,
                    target = ledger.targetCount,
                ),
            )
            return PreToolDecision.Proceed
        }

        // The plan comes first: a step still open means the run is not done, and a step that
        // failed means it cannot be a success whatever the judge would say.
        CompletionGate.openStepsRefusal(ledger, refusals)?.let { refusal ->
            refusals++
            Log.i(TAG, "end_session refused — plan steps still open")
            return PreToolDecision.Deny(refusal)
        }
        CompletionGate.planShortfall(ledger)?.let { why ->
            Log.i(TAG, "end_session success downgraded to partial — $why")
            record(
                RunOutcome(
                    claimed = claimed,
                    verdict = RunOutcome.PARTIAL,
                    // The ✓/✗ step report already says which steps and why; repeating it here
                    // would say it twice in the reply.
                    why = null,
                    proven = ledger.findings.size,
                    target = ledger.targetCount,
                    enforced = true,
                ),
            )
            return PreToolDecision.Proceed
        }

        // No plan, but the run acted on the phone: the success claim is the one step there is.
        // The 10:13 Maps run was exactly this — one deep link, "navigation started", no plan —
        // and without it the claim reached the user unchecked.
        if (ledger.planSteps.isEmpty() && ledger.totalSteps > 0 && stepChecker != null) {
            val verdict = stepChecker.check(ledger, NO_STEP, answer.ifBlank { "(said nothing)" })
            // A check that could not reach the model is not grounds to block; the judge still runs.
            if (!verdict.verified && (verdict.ran || verdict.premature)) {
                if (refusals < CompletionGate.MAX_REFUSALS) {
                    refusals++
                    Log.i(TAG, "end_session refused — not confirmed: ${verdict.reason}")
                    return PreToolDecision.Deny(
                        "Not confirmed on the screen: ${verdict.reason}. Finish it, or call end_session " +
                            "with outcome=\"partial\" and tell the user exactly what you did and did not manage.",
                    )
                }
                record(
                    RunOutcome(
                        claimed = claimed,
                        verdict = RunOutcome.PARTIAL,
                        why = verdict.reason,
                        proven = ledger.findings.size,
                        target = ledger.targetCount,
                        enforced = true,
                    ),
                )
                return PreToolDecision.Proceed
            }
        }

        CompletionGate.refusalFor(claimed, ledger, refusals)?.let { refusal ->
            refusals++
            Log.i(TAG, "end_session refused — ${ledger.findings.size}/${ledger.targetCount} proven")
            return PreToolDecision.Deny(refusal)
        }

        val verdict = judge.judge(ledger, claimed, answer, observed)
        record(verdict)
        val blocking = verdict.verdict == RunOutcome.PARTIAL || verdict.verdict == RunOutcome.FAIL
        if (CompletionJudge.ENFORCE && blocking && refusals < CompletionGate.MAX_REFUSALS) {
            refusals++
            return PreToolDecision.Deny(
                "Checked against what you actually gathered, this is not done yet: " +
                    "${verdict.why ?: "the evidence does not cover what was asked"}. Either finish " +
                    "it and call end_session again, or call end_session with outcome=\"partial\" " +
                    "and tell the user exactly what you did and did not manage.",
            )
        }
        return PreToolDecision.Proceed
    }

    private suspend fun record(value: RunOutcome) {
        outcome = value
        runCatching { controller.setOutcome(value) }
    }

    private companion object {
        const val TAG = "CompletionGate"
        const val END_SESSION = "end_session"

        /** "No plan step": [StepCheckPrompt] then names the whole goal as the claim. */
        const val NO_STEP = -1
    }
}
