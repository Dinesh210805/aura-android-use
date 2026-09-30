package com.aura.aura_ui.agent.ledger

import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import kotlinx.serialization.serializer

/**
 * Persistence for run ledgers — the substrate of resumable runs (Build 3).
 *
 * A run that dies mid-task (app killed, budget spent, crash) leaves its ledger behind; on next
 * launch the surface asks the user whether to resume from it. The whole ledger fits in one JSON
 * blob, so this is a single-key `List<RunLedger>` over [EncryptedJsonStore] (AES-256 at rest),
 * capped at the most recent [MAX_LEDGERS].
 *
 * **Resumability keys on [RunLedger.endReason] alone**, never on `endedAtMs`:
 *  - `null` — the run never called `finalize` → the app died under it (the app-death signal).
 *  - one of [RESUMABLE_END_REASONS] — it stopped before finishing, cleanly.
 *  - `"completed"` / `"abandoned"` fall out of the set → never offered. This is deliberate:
 *    dismissing the resume chip stamps `"abandoned"`, which must stop the re-offer even though the
 *    original app-death ledger still has `endedAtMs == null`.
 */
class RunLedgerStore(
    private val store: EncryptedJsonStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val ser = serializer<RunLedger>()

    fun all(): List<RunLedger> = store.read(LEDGERS_KEY, ser)

    fun get(runId: String): RunLedger? = all().firstOrNull { it.runId == runId }

    /**
     * Upsert by [RunLedger.runId] (latest snapshot wins), newest-first, capped at [MAX_LEDGERS].
     * Uses a synchronous commit so the ledger survives a force-stop — the case resume exists for.
     */
    @Synchronized
    fun save(ledger: RunLedger) {
        val merged = (all().filterNot { it.runId == ledger.runId } + ledger)
            .sortedByDescending { it.startedAtMs }
            .take(MAX_LEDGERS)
        store.writeBlocking(LEDGERS_KEY, merged, ser)
    }

    /** Marks a ledger [ABANDONED] so it is never offered again (chip dismissal). */
    @Synchronized
    fun dismiss(runId: String) {
        val existing = get(runId) ?: return
        save(existing.copy(endReason = ABANDONED, endedAtMs = existing.endedAtMs ?: clock()))
    }

    /**
     * The newest ledger worth resuming: an interrupted run (see resumability rule above), started
     * within [RESUMABLE_WINDOW_MS], that did enough to be worth continuing (a plan, or at least
     * [MIN_RECORDED_STEPS] executed steps — never trivia). Null when nothing qualifies.
     */
    fun latestResumable(): RunLedger? {
        val now = clock()
        return all()
            .filter { isResumable(it, now) }
            .maxByOrNull { it.startedAtMs }
    }

    private fun isResumable(l: RunLedger, now: Long): Boolean {
        val reasonOk = l.endReason == null || l.endReason in RESUMABLE_END_REASONS
        if (!reasonOk) return false
        if (now - l.startedAtMs > RESUMABLE_WINDOW_MS) return false
        return l.planSteps.isNotEmpty() || l.totalSteps >= MIN_RECORDED_STEPS
    }

    companion object {
        const val STORE_NAME = "aura_run_ledger"
        const val LEDGERS_KEY = "ledgers"
        const val MAX_LEDGERS = 5
        const val RESUMABLE_WINDOW_MS = 6L * 60 * 60 * 1000 // 6 hours
        const val MIN_RECORDED_STEPS = 3
        const val ABANDONED = "abandoned"

        /**
         * `paused` is here because a run the human interrupted has *not* finished, and
         * while paused the agent structurally cannot say so — `end_session` is refused
         * along with every other tool. Without this entry a paused run was stamped
         * `completed` and silently dropped. See [PauseCheckpoint].
         */
        // "iterations" = Koog's own node ceiling (2026-09-22). It joined the set the moment it
        // became reachable: with the token ceiling gone it is a *long task that ran out of room*,
        // which is the single case resume exists for — the plan and facts are all still here.
        val RESUMABLE_END_REASONS =
            setOf("budget", "iterations", "failed", "cancelled", PauseCheckpoint.PAUSED)
    }
}

/**
 * Forks a fresh, live ledger from an interrupted one: new [newRunId], [RunLedger.resumedFrom]
 * pointing back, and the clocks reset ([RunLedger.endedAtMs]/[RunLedger.endReason] cleared,
 * [RunLedger.startedAtMs] = [nowMs]). The plan, facts, dead ends, recent-step digest and counts
 * carry over — that reconstruction IS the resume (no lossy chat-history replay).
 */
fun RunLedger.seededResume(newRunId: String, nowMs: Long): RunLedger = copy(
    runId = newRunId,
    resumedFrom = runId,
    startedAtMs = nowMs,
    endedAtMs = null,
    endReason = null,
    // A mid-task spoken instruction belongs to the moment it was said, not to a run picked up
    // later. finalize() already clears it on a clean end, but a run killed by app death never
    // reaches finalize — so the resume path drops it too rather than obeying a stale sentence.
    directive = null,
    directiveAtMs = null,
)
