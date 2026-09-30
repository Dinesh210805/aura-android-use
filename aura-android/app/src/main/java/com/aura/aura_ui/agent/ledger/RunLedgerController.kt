package com.aura.aura_ui.agent.ledger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe mutator around one run's [RunLedger]. All updates are immutable copies;
 * [onMutated] fires with the fresh snapshot after every mutation (Build 3 wires it to the
 * encrypted store for resumable runs — until then it is a no-op).
 */
class RunLedgerController(
    initial: RunLedger,
    private val onMutated: (RunLedger) -> Unit = {},
) {
    private val mutex = Mutex()

    @Volatile
    private var ledger: RunLedger = initial

    fun snapshot(): RunLedger = ledger

    suspend fun setPlan(steps: List<String>) = mutate { l ->
        l.copy(
            planVersion = l.planVersion + 1,
            planSteps = steps.map { PlanStep(text = it) },
            // A new plan starts the progress clock over: the old plan's settled count would
            // otherwise hide the new plan's first steps from the checkpoint.
            settledSteps = 0,
            progressAtCall = l.toolCalls,
        )
    }

    suspend fun markStep(index: Int, status: PlanStepStatus, note: String? = null) = mutate { l ->
        val withStep = if (index in l.planSteps.indices) {
            l.copy(
                planSteps = l.planSteps.mapIndexed { i, step ->
                    if (i == index) step.copy(status = status) else step
                },
            )
        } else {
            l
        }
        note?.let { withStep.withFact(it) } ?: withStep
    }

    /** A `done` claim the step checker accepted: the step is done, with the proof it was shown. */
    suspend fun verifyStep(index: Int, evidence: String) = mutate { l ->
        l.withStep(index) {
            it.copy(
                status = PlanStepStatus.DONE,
                evidence = evidence.trim().take(RunLedgerCaps.MAX_EVIDENCE_CHARS),
                failReason = null,
            )
        }
    }

    /**
     * A `done` claim the step checker turned down. The step stays open until it has used
     * [RunLedgerCaps.MAX_STEP_ATTEMPTS], then it is [PlanStepStatus.FAILED] with [reason], so the
     * run moves on instead of arguing with the checker forever.
     */
    suspend fun rejectStep(index: Int, reason: String) = mutate { l ->
        l.withStep(index) {
            val attempts = it.attempts + 1
            it.copy(
                attempts = attempts,
                status = if (attempts >= RunLedgerCaps.MAX_STEP_ATTEMPTS) PlanStepStatus.FAILED else it.status,
                failReason = reason.trim().take(RunLedgerCaps.MAX_FAIL_REASON_CHARS),
            )
        }
    }

    /** The model gave the step up itself. */
    suspend fun failStep(index: Int, reason: String) = mutate { l ->
        l.withStep(index) {
            it.copy(status = PlanStepStatus.FAILED, failReason = reason.trim().take(RunLedgerCaps.MAX_FAIL_REASON_CHARS))
        }
    }

    /**
     * Count one turn's tool calls for the progress checkpoint. Runs after the turn's tools, so a
     * step settled during the turn resets the clock to include the turn that settled it, and a
     * failure in the turn becomes the surprise the next render reports.
     */
    suspend fun noteTurn(calls: Int, failure: String?) = mutate { l ->
        val now = l.toolCalls + calls
        val settled = l.planSteps.count { it.settled }
        val progressed = settled > l.settledSteps
        val progressAt = if (progressed) now else l.progressAtCall
        val before = (l.toolCalls - l.progressAtCall) / RunLedgerCaps.CHECKPOINT_EVERY
        val after = (now - progressAt) / RunLedgerCaps.CHECKPOINT_EVERY
        l.copy(
            toolCalls = now,
            progressAtCall = progressAt,
            stalledCalls = if (!progressed && after > before) now - progressAt else null,
            settledSteps = settled,
            // A rejected mark_step is a failed call like any other, so it lands here too.
            surprise = failure?.trim()?.take(RunLedgerCaps.MAX_FAIL_REASON_CHARS) ?: l.surprise,
            surpriseAtCall = if (failure != null) now else l.surpriseAtCall,
        )
    }

    suspend fun noteFact(text: String) = mutate { l -> l.withFact(text) }

    /**
     * Record what the run owes the user, declared with the plan. Blank or absent parts are left
     * alone, so a re-plan that only restates the steps cannot quietly erase the target count the
     * completion gate keys on.
     */
    suspend fun setContract(deliverable: String?, targetCount: Int?) = mutate { l ->
        val text = deliverable?.trim()?.take(RunLedgerCaps.MAX_DELIVERABLE_CHARS)?.takeIf { it.isNotEmpty() }
        val count = targetCount?.takeIf { it in 1..RunLedgerCaps.MAX_TARGET_COUNT }
        l.copy(
            deliverable = text ?: l.deliverable,
            targetCount = count ?: l.targetCount,
        )
    }

    /** Store one verified finding. The caller has already matched its quote against observed text. */
    suspend fun addFinding(finding: Finding) = mutate { l ->
        val item = finding.item.trim().take(RunLedgerCaps.MAX_FINDING_CHARS)
        if (item.isEmpty()) {
            l
        } else {
            l.copy(
                findings = (
                    l.findings + finding.copy(
                        item = item,
                        quote = finding.quote.trim().take(RunLedgerCaps.MAX_QUOTE_CHARS),
                    )
                    ).takeLast(RunLedgerCaps.MAX_FINDINGS),
            )
        }
    }

    suspend fun setOutcome(outcome: RunOutcome) = mutate { l -> l.copy(outcome = outcome) }

    /** Store pre-task research. Ignored once the run has ended, so a late result cannot land. */
    suspend fun setResearch(note: ResearchNote) = mutate { l -> if (l.endedAtMs != null) l else l.copy(research = note) }

    /**
     * Record the user's latest spoken mid-task instruction (voice steering).
     *
     * REPLACES rather than appends: "actually, search for B instead" supersedes whatever was
     * said before it, and two live directives would leave the model choosing between them.
     * Blank input clears the directive, so a caller can cancel a steer without a second API.
     */
    suspend fun setDirective(text: String, nowMs: Long = System.currentTimeMillis()) = mutate { l ->
        val trimmed = text.trim().take(RunLedgerCaps.MAX_DIRECTIVE_CHARS)
        if (trimmed.isEmpty()) l.copy(directive = null, directiveAtMs = null)
        else l.copy(directive = trimmed, directiveAtMs = nowMs)
    }

    /**
     * Point the run at a different goal (voice redirect).
     *
     * Clears the plan as well, and that is the whole point: the checklist was built for the
     * OLD goal, so leaving it in place has the model dutifully ticking off steps that no
     * longer serve what the user now wants. Bumping [RunLedger.planVersion] here too makes
     * the change visible in the rendered block rather than looking like a plan that silently
     * emptied itself. A blank goal is ignored — never let a bad parse erase the run's target.
     */
    suspend fun retarget(newGoal: String) = mutate { l ->
        val trimmed = newGoal.trim()
        if (trimmed.isEmpty()) {
            l
        } else {
            l.copy(
                goal = trimmed,
                planVersion = l.planVersion + 1,
                planSteps = emptyList(),
                settledSteps = 0,
                progressAtCall = l.toolCalls,
            )
        }
    }

    suspend fun recordToolStep(tool: String, label: String?, ok: Boolean, screenChanged: Boolean?) = mutate { l ->
        val record = StepRecord(tool = tool, label = label, ok = ok, screenChanged = screenChanged)
        l.copy(
            recentSteps = (l.recentSteps + record).takeLast(RunLedgerCaps.MAX_RECENT_STEPS),
            totalSteps = l.totalSteps + 1,
            failedSteps = l.failedSteps + if (ok) 0 else 1,
        )
    }

    suspend fun recordDeadEnd(text: String) = mutate { l ->
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed in l.deadEnds) {
            l
        } else {
            l.copy(deadEnds = (l.deadEnds + trimmed).takeLast(RunLedgerCaps.MAX_DEAD_ENDS))
        }
    }

    /**
     * Close the run out. The live [RunLedger.directive] is dropped here: it was an instruction
     * about work that is now over, and this ledger may be picked up again by a resume. Leaving
     * it would have a resumed run silently obey a sentence the user spoke into a different
     * situation, possibly hours earlier.
     */
    suspend fun finalize(endReason: String, nowMs: Long = System.currentTimeMillis()) = mutate { l ->
        l.copy(endReason = endReason, endedAtMs = nowMs, directive = null, directiveAtMs = null)
    }

    /** Applies [transform] under the lock; notifies [onMutated] even when the value is unchanged. */
    private suspend inline fun mutate(transform: (RunLedger) -> RunLedger) {
        val updated = mutex.withLock {
            ledger = transform(ledger)
            ledger
        }
        onMutated(updated)
    }

    private inline fun RunLedger.withStep(index: Int, change: (PlanStep) -> PlanStep): RunLedger =
        if (index !in planSteps.indices) this
        else copy(planSteps = planSteps.mapIndexed { i, step -> if (i == index) change(step) else step })

    private fun RunLedger.withFact(text: String): RunLedger {
        val trimmed = text.trim().take(RunLedgerCaps.MAX_FACT_CHARS)
        if (trimmed.isEmpty()) return this
        return copy(facts = (facts + trimmed).takeLast(RunLedgerCaps.MAX_FACTS))
    }
}
