package com.aura.aura_ui.agent.ledger

import kotlinx.serialization.Serializable

/**
 * The Run Ledger — harness-owned working memory for one agent run.
 *
 * The agent's chat history is lossy by design (stale-perception eviction, autocompaction);
 * this ledger is the durable plot: the goal, the model's own plan checklist, a digest of
 * what actually executed, key facts the model noted, and dead ends discovered. It is
 * re-rendered into the model's context every turn (see RunLedgerRenderer + the vision
 * strategy), so history can be dropped aggressively without the run losing its thread.
 *
 * Mutated only through [RunLedgerController] (immutable copies); serialized as-is to the
 * encrypted store for resumable runs.
 */
@Serializable
data class RunLedger(
    val runId: String,
    val goal: String,
    val provider: String,
    val modelId: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val endReason: String? = null,
    /** Run id this ledger was seeded from, when the run is a resume of an interrupted one. */
    val resumedFrom: String? = null,
    val planVersion: Int = 0,
    val planSteps: List<PlanStep> = emptyList(),
    /**
     * The user's latest spoken mid-task instruction, or null.
     *
     * Deliberately NOT a fact. Facts render inside the untrusted fence
     * ("screen-derived; may be stale or wrong") and are the first thing the renderer trims
     * to fit its size cap — both fatal here. A directive comes from the human, is the most
     * authoritative thing in the ledger, and must survive every trim, so it renders in the
     * trusted region beside the goal. See [RunLedgerRenderer].
     *
     * Single-valued rather than a list: a redirect SUPERSEDES the previous instruction. Two
     * live directives would leave the model to guess which one still applies.
     */
    val directive: String? = null,
    /** When [directive] was set — lets the renderer say how recent the instruction is. */
    val directiveAtMs: Long? = null,
    /**
     * What the user ends up with, in the model's own words at `set_plan` time ("a summary of 10
     * Reddit posts"). Rendered every turn beside the proof count, which is what keeps a long run
     * pointed at the thing it was asked for rather than at whatever the last screen suggested.
     */
    val deliverable: String? = null,
    /**
     * How many items the goal asks for, or null when it asks for no particular number. The count
     * gate refuses a success claim while [findings] holds fewer than this.
     */
    val targetCount: Int? = null,
    /**
     * Verified proof collected this run — see `record_finding`. Separate from [facts] on purpose:
     * facts are free-text notes and the renderer trims them first, while a finding carries a quote
     * the harness matched against text the run actually read, and it is what the completion gate
     * counts. Also the working memory that lets a harvest continue past one screen.
     */
    val findings: List<Finding> = emptyList(),
    /** How the run actually ended, once the gate and the judge have had their say. */
    val outcome: RunOutcome? = null,
    val facts: List<String> = emptyList(),
    val deadEnds: List<String> = emptyList(),
    /** Rolling window of the most recent executed steps (full history lives in the trace). */
    val recentSteps: List<StepRecord> = emptyList(),
    val totalSteps: Int = 0,
    val failedSteps: Int = 0,
    /** Pre-task research from the web (TaskResearch). Advisory, screen-trumped, may never arrive. */
    val research: ResearchNote? = null,
    /**
     * Every tool call this run, including bookkeeping and filler (`load_tools` for a tool already
     * loaded). Unlike [totalSteps], which counts only screen actions, this is the clock the
     * progress checkpoint runs on: a model looping on harmless calls is exactly what it must see.
     */
    val toolCalls: Int = 0,
    /** [toolCalls] when a plan step was last settled (verified done, failed or skipped). */
    val progressAtCall: Int = 0,
    /** How many plan steps were settled at [progressAtCall] — tells a new settlement from an old one. */
    val settledSteps: Int = 0,
    /** The latest thing that went wrong (a refusal, a failed call, a rejected step), for the checkpoint. */
    val surprise: String? = null,
    /** [toolCalls] after the turn that produced [surprise]. */
    val surpriseAtCall: Int? = null,
    /**
     * Calls without a settled step, set on the turn that crossed another
     * [RunLedgerCaps.CHECKPOINT_EVERY] and null on every other turn — so the checkpoint asks once
     * per stretch instead of on every turn after it.
     */
    val stalledCalls: Int? = null,
)

/** What pre-task research found: the page's host and a few lines of its text. */
@Serializable
data class ResearchNote(val source: String, val text: String)

@Serializable
data class PlanStep(
    val text: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    /** What on screen showed this step happened, as the step checker accepted it. */
    val evidence: String? = null,
    /** Rejected `done` claims so far. [PlanStepStatus.FAILED] at [RunLedgerCaps.MAX_STEP_ATTEMPTS]. */
    val attempts: Int = 0,
    /** Why the step failed: the checker's last reason, or the model's own when it gave up. */
    val failReason: String? = null,
) {
    /** Nothing more will happen to this step: it is done, given up, or skipped. */
    val settled: Boolean
        get() = status == PlanStepStatus.DONE || status == PlanStepStatus.FAILED || status == PlanStepStatus.SKIPPED
}

@Serializable
enum class PlanStepStatus { PENDING, IN_PROGRESS, DONE, SKIPPED, FAILED }

/**
 * One piece of proof: what the agent found, and the verbatim screen text backing it.
 *
 * The quote is checked against what the run actually observed before this is ever stored
 * (`ObservedText`), so a finding in the ledger is evidence, not a claim.
 */
@Serializable
data class Finding(
    val item: String,
    val quote: String,
    /** How many read-results the run had taken when this was recorded — ordering, not a step id. */
    val afterReads: Int = 0,
)

/**
 * The run's honest ending. [claimed] is what the model said (`end_session`'s outcome, or null when
 * it never called it); [verdict] is what the harness concluded — success | partial | fail |
 * unverified. They differ exactly when the model over-claimed, which is the thing worth recording.
 */
@Serializable
data class RunOutcome(
    val claimed: String? = null,
    val verdict: String,
    val why: String? = null,
    val proven: Int = 0,
    val target: Int? = null,
    /**
     * The verdict came from an enforced check — the plan (a step failed, skipped or stayed open)
     * or the step checker — not from the shadow judge, so it is always spoken.
     */
    val enforced: Boolean = false,
) {
    companion object {
        const val SUCCESS = "success"
        const val PARTIAL = "partial"
        const val FAIL = "fail"

        /** The judge could not be reached or answered unusably. Never blocks a run. */
        const val UNVERIFIED = "unverified"
    }
}

@Serializable
data class StepRecord(
    val tool: String,
    val label: String? = null,
    val ok: Boolean,
    /** E2 post_action_observation signal; null when the result carried no bundle. */
    val screenChanged: Boolean? = null,
)

/** Size caps keeping the rendered ledger block small and the store bounded. */
object RunLedgerCaps {
    const val MAX_FACTS = 10
    const val MAX_FACT_CHARS = 120

    /**
     * A spoken instruction is one or two sentences. Capped a little above a fact because it
     * carries a re-perceive clause as well as the redirect itself.
     */
    const val MAX_DIRECTIVE_CHARS = 300
    const val MAX_DEAD_ENDS = 8
    const val MAX_RECENT_STEPS = 10

    /**
     * Findings are capped well above facts: a "check 20 posts" goal must be able to hold its
     * whole harvest, because the summary is written from these and nothing else.
     */
    const val MAX_FINDINGS = 30
    const val MAX_FINDING_CHARS = 160
    const val MAX_QUOTE_CHARS = 200
    const val MAX_DELIVERABLE_CHARS = 160

    /** Refuse an absurd target rather than letting a typo'd 1000 make every run un-finishable. */
    const val MAX_TARGET_COUNT = 100

    /** Rejected `done` claims before a step is marked failed and the run moves on. */
    const val MAX_STEP_ATTEMPTS = 3

    /** The progress checkpoint asks after this many calls without a settled step, and again every as many after. */
    const val CHECKPOINT_EVERY = 4
    const val MAX_EVIDENCE_CHARS = 200
    const val MAX_FAIL_REASON_CHARS = 200
}
