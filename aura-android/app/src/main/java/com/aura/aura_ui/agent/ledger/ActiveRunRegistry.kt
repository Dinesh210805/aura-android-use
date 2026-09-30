package com.aura.aura_ui.agent.ledger

/**
 * The one place anything outside a run can reach the run that is happening RIGHT NOW.
 *
 * ### Why this has to exist
 *
 * A run's [RunLedgerController] is a local `val` inside `AuraAgent.runSpike`. That is fine for
 * everything the run does to itself, but it means no other component can touch the ledger while
 * the run is alive — and the ledger is the only channel by which anything reaches a running
 * agent's context (the vision strategy re-renders it before every LLM request). Without a
 * registry, "tell the agent the user changed their mind" is not merely unimplemented, it is
 * unreachable.
 *
 * ### Why a process singleton, and why keyed unregister
 *
 * Process-global for the same reason `ControlLockStore.shared` is: the conversation plane and
 * the run are different objects that must agree on one answer to "what is running?". Handing
 * each an instance gives a steer that reaches nobody.
 *
 * [unregister] is keyed by run id rather than blindly clearing. Runs are *supposed* to be
 * one-at-a-time (`CompanionTools` refuses a second phone task), but that guard sits above this
 * layer and cannot see a run started from the overlay's text entry. If two ever overlap, an
 * unkeyed clear would have the OLDER run's teardown silently unregister the NEWER run — leaving
 * a live run unsteerable with no error anywhere. Keying it makes that impossible rather than
 * unlikely.
 *
 * Reads are lock-free: callers get a snapshot reference and mutate through
 * [RunLedgerController], which is itself Mutex-guarded. Worst case under a race is steering a
 * run that finished microseconds ago, which the controller absorbs harmlessly.
 */
class ActiveRunRegistry {

    @Volatile
    private var entry: Entry? = null

    private data class Entry(val runId: String, val controller: RunLedgerController)

    /** The controller of the run currently in flight, or null when nothing is running. */
    val current: RunLedgerController? get() = entry?.controller

    /** Run id of the run currently in flight, or null. */
    val currentRunId: String? get() = entry?.runId

    val isRunning: Boolean get() = entry != null

    /** Claim the slot for [runId]. Last writer wins: the newest run is the one worth steering. */
    fun register(runId: String, controller: RunLedgerController) {
        entry = Entry(runId, controller)
    }

    /**
     * Release the slot, but only if [runId] still owns it. A run whose teardown arrives after a
     * newer run registered must not clear the newer one. Returns true when the slot was released.
     */
    fun unregister(runId: String): Boolean {
        if (entry?.runId != runId) return false
        entry = null
        return true
    }

    companion object {
        /**
         * The one registry for this process. Constructor stays public so tests build their own
         * instance and never touch this — the same arrangement `ControlLockStore` uses.
         */
        val shared: ActiveRunRegistry by lazy { ActiveRunRegistry() }
    }
}
