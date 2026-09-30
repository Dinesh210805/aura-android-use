package com.aura.aura_ui.agent.ledger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced write-through from [RunLedgerController.onMutated] to [RunLedgerStore].
 *
 * Every gesture mutates the ledger; persisting on each one would thrash the encrypted store. So a
 * mutation only *schedules* a save [debounceMs] out, and a newer mutation cancels the pending one —
 * the store sees one write per quiet period, always the latest snapshot.
 *
 * [flush] is the correctness seam: on run teardown the finally-block calls it so the *final*
 * ledger state (with its `endReason` stamped by `finalize`) lands synchronously, instead of a
 * 500ms-delayed write that would be lost when the run scope dies. Without it, a cleanly-completed
 * run's `endReason="completed"` might never reach disk and the run would look app-dead → wrongly
 * re-offered as resumable.
 */
class RunLedgerPersister(
    private val store: RunLedgerStore,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500L,
) {
    private var pending: Job? = null

    @Volatile
    private var latest: RunLedger? = null

    /** Wire as `RunLedgerController(onMutated = persister::onMutated)`. */
    fun onMutated(ledger: RunLedger) {
        latest = ledger
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            store.save(ledger)
        }
    }

    /** Cancels any pending debounce and writes the latest snapshot now. Idempotent; no-op if empty. */
    fun flush() {
        pending?.cancel()
        latest?.let { store.save(it) }
    }
}
