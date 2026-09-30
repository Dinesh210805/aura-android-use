package com.aura.aura_ui.agent.proof

import com.aura.aura_ui.agent.ledger.PlanStepStatus
import com.aura.aura_ui.agent.ledger.RunLedger
import com.aura.aura_ui.agent.ledger.RunOutcome

/**
 * The counted half of the completion contract: a success claim is refused while the run holds
 * fewer verified findings than the goal asked for.
 *
 * Pure decisions, no model call — which is the point. `CompletionEvidence` (server side) already
 * proved that a tool contract stops an over-claim where prose does not, and this is the same shape
 * applied to the other observed failure: "check 10 posts" answered from two screens. The count
 * comes from the model's own `set_plan` declaration rather than from parsing the goal, so it works
 * for posts, prices, emails or settings without a per-task rule anywhere.
 *
 * Under-declaring the target to slip past this is possible, and is left to the judge, which reads
 * the user's original words instead of the contract.
 *
 * Known hole, the same shape as `CompletionEvidence`'s goal_type one: the count is blind to WHERE
 * proof came from. [ObservedText] is one flat corpus, so ten quotes lifted from a single search
 * result page satisfy "read 10 posts" as well as ten posts would. Closing it means per-source
 * attribution on every read tool, which is a larger change than the over-claim it prevents; the
 * judge sees the step list and is the layer expected to notice.
 */
object CompletionGate {

    /**
     * How many times one run may be sent back for more proof. Mirrors
     * `ActionGuard.MAX_FINISH_BLOCKS`: a gate with no ceiling turns a model that cannot find the
     * remaining items into a run that never terminates, which is worse than an honest "3 of 10".
     */
    const val MAX_REFUSALS = 2

    /** The refusal to hand back as an error tool-result, or null to let `end_session` through. */
    fun refusalFor(outcome: String?, ledger: RunLedger, refusalsSoFar: Int): String? {
        if (outcome?.lowercase()?.trim() != RunOutcome.SUCCESS) return null
        val target = ledger.targetCount ?: return null
        val proven = ledger.findings.size
        if (proven >= target) return null
        if (refusalsSoFar >= MAX_REFUSALS) return null
        val missing = target - proven
        return "Not yet — you have proof for $proven of $target. Go back and get the other " +
            "$missing: scroll or open the next one, read it, and call record_finding for each. " +
            "If they genuinely are not reachable, call end_session with outcome=\"partial\" and " +
            "say plainly that you covered $proven of $target and why — that is always allowed, " +
            "and an honest partial beats a success the user cannot rely on."
    }

    /**
     * The refusal for a success claim while plan steps are still open, or null. Capped by
     * [MAX_REFUSALS] like the count gate; past the cap [planShortfall] reports the open steps.
     */
    fun openStepsRefusal(ledger: RunLedger, refusalsSoFar: Int): String? {
        if (refusalsSoFar >= MAX_REFUSALS) return null
        val open = ledger.planSteps.withIndex().filter { !it.value.settled }
        if (open.isEmpty()) return null
        val names = open.joinToString("; ") { "${it.index + 1}. ${it.value.text}" }
        return "Not yet — these plan steps are not done: $names. Do them and mark_step each one " +
            "done with evidence, or mark_step it failed with why. Then call end_session again."
    }

    /**
     * Why a success claim can only be partial, from the plan alone: a step failed, was skipped, or
     * is still open after the refusals ran out. Null when every step is done (or there is no plan).
     *
     * Skipped counts: it never goes through the step checker, so "skip it after two rejections,
     * then claim success" would otherwise be a quiet way round the whole check.
     */
    fun planShortfall(ledger: RunLedger): String? {
        val missing = ledger.planSteps.filter { it.status != PlanStepStatus.DONE }
        if (missing.isEmpty()) return null
        return missing.joinToString(" ") { step ->
            "\"${step.text}\" was ${if (step.status == PlanStepStatus.SKIPPED) "skipped" else "not done"}${step.failReason?.let { ": $it" } ?: ""}."
        }
    }

    /**
     * One line per plan step for the reply the user reads: ✓ with the evidence the checker
     * accepted, ✗ with why not. Built from the ledger rather than the model's words, so it can only
     * say what was verified. Null for a run with fewer than two steps — "done" says it all there.
     */
    fun stepReport(ledger: RunLedger): String? {
        if (ledger.planSteps.size < 2) return null
        return ledger.planSteps.joinToString("\n") { step ->
            when (step.status) {
                PlanStepStatus.DONE -> "✓ ${step.text}${step.evidence?.let { " — $it" } ?: ""}"
                PlanStepStatus.SKIPPED -> "– ${step.text} (skipped)"
                else -> "✗ ${step.text}${step.failReason?.let { " — $it" } ?: " — not done"}"
            }
        }
    }

    /**
     * The correction appended to the run's reply when the harness disagrees with the model, or
     * null when the reply can stand as written.
     *
     * Appended rather than replacing the model's text: the answer it wrote is usually correct
     * about what it DID find, and the thing worth adding is the shortfall. The line is plain
     * prose because both the chat bubble and the Live plane read the same string aloud.
     *
     * Silent when the model already claimed `partial` or `failure` itself — its own reason IS the
     * reply in that case, so a correction would only repeat back what the user just heard. Only a
     * claimed success, or a run that claimed nothing at all, earns a footnote.
     *
     * [trustJudge] is what keeps shadow mode silent. A counted shortfall is arithmetic and always
     * speaks; a verdict is one model's opinion, and until [CompletionJudge.ENFORCE] says that
     * opinion has been measured against human scores it is recorded only. Contradicting a run that
     * worked would corrupt the very eval used to decide whether the judge is worth trusting.
     */
    fun correctionFor(outcome: RunOutcome, trustJudge: Boolean = CompletionJudge.ENFORCE): String? {
        if (outcome.claimed != null && outcome.claimed != RunOutcome.SUCCESS) return null
        // An enforced check, not the shadow judge's opinion: always spoken. The step report says which.
        if (outcome.enforced) {
            return "Being straight with you: I only got part of this done." +
                (outcome.why?.takeIf { it.isNotBlank() }?.let { " ${it.trim().replaceFirstChar(Char::uppercase)}." } ?: "")
        }
        val target = outcome.target
        if (target != null && outcome.proven < target) {
            return "Being straight with you: I only got part of this done — ${outcome.proven} of $target."
        }
        return if (trustJudge) verdictLine(outcome) else null
    }

    private fun verdictLine(outcome: RunOutcome): String? = when (outcome.verdict) {
        RunOutcome.PARTIAL -> buildString {
            append("Being straight with you: I only got part of this done")
            outcome.target?.let { append(" — ${outcome.proven} of $it") }
            append(".")
            outcome.why?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.trim()) }
        }
        RunOutcome.FAIL -> buildString {
            append("I don't think that actually worked.")
            outcome.why?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.trim()) }
        }
        // UNVERIFIED stays silent: the judge being unreachable is our problem, not something to
        // narrate at the user, and SUCCESS needs no footnote.
        else -> null
    }
}

