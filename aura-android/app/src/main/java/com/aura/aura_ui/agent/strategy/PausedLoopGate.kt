package com.aura.aura_ui.agent.strategy

import kotlinx.coroutines.delay

/**
 * Waits out a human-held control lock instead of calling tools into it.
 *
 * ### Why this exists (2026-08-05 device traces)
 *
 * `ControlLock` refuses every tool while the human holds the wheel, and that refusal arrives
 * as an ordinary tool error whose message said "wait and try again in a moment". The model
 * did exactly that — `get_ui_tree` five times in a row, then `end_session` four times with
 * the wording changed on each attempt, as though the phrasing were what had been rejected.
 * Each was a full ~20k-token round trip against a free-tier quota that two runs exhausted.
 *
 * A loop that cannot touch the phone has nothing useful to think about. Waiting is both
 * cheaper and more honest than reasoning in circles.
 *
 * ### Why not exempt `end_session` instead
 *
 * That was the first design, and `ControlLock`'s own KDoc argues against it: *"An exemption
 * list … is a list someone eventually gets wrong, and it would be the one hole in a guard
 * whose entire value is having none."* Stopping the loop reaches the same outcome without
 * opening the hole — a paused run never attempts `end_session` in the first place.
 *
 * ### Why bounded
 *
 * `ControlLockStore.AUTO_RESUME_AFTER_MS` (30 s) already releases a stale pause. A wait
 * longer than that means something is wrong, and the run should end with an honest message
 * rather than leave the user watching a phone that appears frozen.
 *
 * Pure Kotlin with injected predicates, so it unit-tests on `runTest`'s virtual clock — the
 * same shape as [EmptyTurnPolicy] and [PerceptionEvictionPolicy].
 */
class PausedLoopGate(
    private val isPaused: () -> Boolean,
    private val pollMs: Long = 250L,
    private val maxWaitMs: Long = 30_000L,
) {
    /** @return true if the lock cleared (or was never held); false if the bound elapsed. */
    suspend fun awaitResume(): Boolean {
        if (!isPaused()) return true
        var waited = 0L
        while (waited < maxWaitMs) {
            delay(pollMs)
            waited += pollMs
            if (!isPaused()) return true
        }
        return false
    }
}
