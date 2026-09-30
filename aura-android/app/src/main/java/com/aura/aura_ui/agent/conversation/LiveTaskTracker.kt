package com.aura.aura_ui.agent.conversation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks the ONE long-running Live tool call in flight (today: `drive_phone`) so the session can
 * honour Gemini's asynchronous function-calling contract.
 *
 * ## Why this exists
 *
 * `drive_phone` is declared `behavior: NON_BLOCKING`, and Google's contract for a NON_BLOCKING call
 * is that the client sends a `functionResponse` **immediately** — it must not wait for the function
 * to finish. The pre-fix code did the opposite: it suspended inside the tool-call handler for the
 * whole task (30-90 s) and only then sent the response.
 *
 * The model therefore emitted a call and received **nothing** for minutes. With no result in its
 * context it did one of exactly two things, both of which users reported:
 *
 *  - invented an outcome ("done, I've ordered it") — a hallucinated result, because the only thing
 *    it could do with an empty slot was fill it; or
 *  - kept conversing and never re-issued the call — the "says it will do it and keeps listening"
 *    symptom.
 *
 * Instructing the model not to do this could never work: [Persona] already forbids both in plain
 * words. You cannot prompt a model out of a situation where reality never reached its context.
 *
 * ## The three-phase contract this enables
 *
 * | Phase    | scheduling  | Why |
 * |----------|-------------|-----|
 * | ack      | `SILENT`    | The model learns the task started but is not prompted to announce it — the persona's own opening line already covers that, and two announcements is one too many. |
 * | progress | `SILENT`    | Real state accumulates while the task runs, so "is it done yet?" is answered from fact instead of imagination. |
 * | result   | `INTERRUPT` | The moment the task genuinely finishes, the model stops what it is doing and reports the real outcome. |
 *
 * ## Progress throttling
 *
 * Agent progress fires once per tool call, which on a busy task is far faster than a conversation
 * needs. Unthrottled it would flood the socket and bury the useful transitions. [shouldEmitProgress]
 * enforces a minimum interval AND drops repeats of the text already sent — a task that sits on the
 * same step for a while contributes one message, not twenty.
 *
 * Pure Kotlin, no Android dependencies, so it unit-tests directly — the same shape as
 * [InterruptGate] and the agent plane's `ActionGuard`.
 */
class LiveTaskTracker(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** The call currently running, as the session needs to address it. */
    data class InFlight(
        val callId: String,
        val name: String,
        /** The task text as the model phrased it — echoed into the ack so the model sees what it started. */
        val task: String,
        val startedAtMs: Long,
    )

    @Volatile
    private var current: InFlight? = null

    private var lastProgressAtMs = 0L
    private var lastProgressText: String? = null

    val inFlight: InFlight? get() = current

    val isRunning: Boolean get() = current != null

    /**
     * Record a long-running call as started. Returns false when one is already in flight — the
     * conversation plane runs one phone task at a time, and a second concurrent `drive_phone` would
     * have two agent runs fighting over the same screen.
     */
    fun onStarted(callId: String, name: String, task: String): Boolean {
        if (current != null) return false
        current = InFlight(callId, name, task, clock())
        lastProgressAtMs = 0L
        lastProgressText = null
        return true
    }

    /** Clear the in-flight slot and return what was running (null when nothing was). */
    fun onFinished(): InFlight? = current.also { current = null }

    /**
     * Should this progress line be forwarded to the model?
     *
     * False when nothing is running, when the identical line was already sent (the agent re-reports
     * the same step while it retries), or when the previous line went out less than
     * [MIN_PROGRESS_INTERVAL_MS] ago. The first progress line of a task always passes — that is the
     * one that tells the model the work actually began moving.
     */
    fun shouldEmitProgress(text: String): Boolean {
        if (current == null) return false
        if (text.isBlank()) return false
        if (text == lastProgressText) return false
        val now = clock()
        if (lastProgressAtMs != 0L && now - lastProgressAtMs < MIN_PROGRESS_INTERVAL_MS) return false
        lastProgressAtMs = now
        lastProgressText = text
        return true
    }

    companion object {
        /**
         * Minimum gap between progress messages pushed to the model. Four seconds is well under the
         * time a user waits before asking "how's it going?", and well over the cadence at which the
         * agent emits tool-level progress.
         */
        const val MIN_PROGRESS_INTERVAL_MS = 4_000L

        /** `scheduling` value: the model absorbs the fact without being prompted to speak about it. */
        const val SCHEDULING_SILENT = "SILENT"

        /** `scheduling` value: the model stops what it is doing and reports this now. */
        const val SCHEDULING_INTERRUPT = "INTERRUPT"

        /**
         * Whether the async (NON_BLOCKING) contract is usable on this connection.
         *
         * Process-scoped and mutable because the answer is a property of the *server*, not of our
         * code: Google's docs said async function calling was "not yet supported" on
         * `gemini-3.1-flash-live-preview` at the time of writing, but such lines age badly and the
         * model id churns. So we always ATTEMPT the correct protocol and let runtime evidence
         * demote us — see [demoteToBlocking]. Surviving across reconnects is the point: re-learning
         * the same rejection on every reconnect would mean every session starts with a failure.
         */
        private val asyncSupported = AtomicBoolean(true)

        val isAsyncSupported: Boolean get() = asyncSupported.get()

        /**
         * Called when the server rejected a session in which we sent an early acknowledgement.
         * Falls back to the pre-fix blocking behaviour for the rest of the process: degraded (the
         * hallucination window returns) but working, which beats a session that will not open.
         */
        fun demoteToBlocking(): Boolean = asyncSupported.getAndSet(false)

        /** Test seam — restores the optimistic default. */
        fun resetAsyncSupportForTest() = asyncSupported.set(true)
    }
}
