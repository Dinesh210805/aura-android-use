package com.aura.aura_ui.agent.ledger

/**
 * Spec 2026-07-31 — *"Pause must checkpoint, not merely stop."*
 *
 * ### The hole this closes
 *
 * While the human holds the control lock, **every** tool call is refused — including
 * `end_session`, because `ControlLock.evaluate` deliberately takes no tool name and an
 * exemption list is a list someone eventually gets wrong. The design doc accepts that cost
 * explicitly and says the answer is "the Run-Ledger checkpoint, which is loop-level work".
 * This is that work.
 *
 * The consequence of not doing it was not a missing nicety, it was silent data loss:
 *
 *  1. The user picks up their phone mid-task; the lock passes to them.
 *  2. Every tool call the agent makes is refused with `paused_by_user`.
 *  3. The model, unable to do anything, stops asking for tools and returns a plain final
 *     answer ("I can't continue while you're using your phone").
 *  4. `AuraAgent.runSpike` initialises `endReason = "completed"` and only overwrites it for
 *     budget / cancel / failure — a plain final answer leaves it at the default.
 *  5. `"completed"` is deliberately **absent** from [RunLedgerStore.RESUMABLE_END_REASONS],
 *     so the ledger is never offered for resume.
 *
 * The user's task disappeared, and it disappeared labelled *completed* — the exact
 * false-success shape this codebase treats as the worst available outcome.
 *
 * ### Why only `completed` is rewritten
 *
 * `failed`, `budget` and `cancelled` are already resumable *and* already tell the truth
 * about what happened. Relabelling them "paused" would trade a real diagnosis for no gain.
 * `completed` is the only reason that is both unreachable-by-honest-means while paused
 * (its one confirming path, `end_session`, is refused) and non-resumable — so it is the
 * only one that can silently lose work.
 */
object PauseCheckpoint {

    /**
     * A run that stopped because the human took the wheel. Resumable, and honest: AURA
     * genuinely does not know whether the task finished, because the tool that would have
     * confirmed it was refused.
     */
    const val PAUSED = "paused"

    /** The optimistic default `runSpike` starts with, overwritten only on budget/cancel/failure. */
    const val COMPLETED = "completed"

    /**
     * The end reason to persist for a run that is finishing right now.
     *
     * @param computed what the run itself concluded.
     * @param pausedByHuman whether the human still holds the lock at teardown. Read from the
     *   live store rather than mirrored into a field, so there is no second copy to drift.
     */
    fun endReasonFor(computed: String, pausedByHuman: Boolean): String =
        if (pausedByHuman && computed == COMPLETED) PAUSED else computed

    /**
     * The note written into the ledger the moment a pause begins.
     *
     * Recorded as a fact rather than a status field for one reason: facts are re-rendered
     * into the model's context on every turn, so a resumed run is *told* it was interrupted
     * by the user instead of silently re-deriving it. It also forces a ledger mutation,
     * which is what drives the write-through to disk.
     */
    fun checkpointNote(task: String?): String =
        if (task.isNullOrBlank()) {
            "Paused: the user picked up their phone. Work stopped here, not finished."
        } else {
            "Paused while working on \"$task\" — the user picked up their phone. " +
                "Work stopped here, not finished."
        }
}
