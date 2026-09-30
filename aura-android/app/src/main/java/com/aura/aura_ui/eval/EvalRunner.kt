package com.aura.aura_ui.eval

import android.util.Log
import com.aura.aura_ui.agent.AgentRunBridge
import com.aura.aura_ui.agent.llm.QuotaExhaustion
import com.aura.aura_ui.mcp.log.SessionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the eval suite on-device, one task at a time, and stops the moment the provider says no.
 *
 * ### The failure this is built around
 *
 * A free-tier key gives 500 requests a day. A 40-task suite costs roughly 300. There is no second
 * attempt, so a suite that dies at task 22 does not cost 18 tasks — it costs the day. The old
 * laptop-driven runner could not even see the failure: it slept, broadcast the next goal, and
 * wrote 18 more "failures" that were really just the same rate limit over and over.
 *
 * So the contract here is: **on a quota refusal, stop and keep everything.** The user swaps in a
 * fresh key and calls [resume], which picks up at the task that failed. Nothing already earned is
 * re-run, because re-running it would spend the very budget that just ran out.
 *
 * ### Why a rate-limited task is not a failure
 *
 * It is scored [EvalVerdict.INVALID] automatically. The agent was never given a chance to be good
 * or bad at the task; the harness ran out of money. Counting that as a failure would make the
 * suite's headline number a measure of the user's billing tier. The runner assigns that verdict
 * itself so a tired human at task 30 never has to remember the rule.
 *
 * ### Why this class says nothing out loud
 *
 * It used to. It built its own [android.speech.tts.TextToSpeech] to announce tasks and read
 * answers back, and that broke the thing it was trying to help with: Android's TTS service is
 * shared, so a second engine speaking with QUEUE_FLUSH cut the overlay off mid-sentence. The agent
 * looked like it was being killed before it could reply — it was, by us.
 *
 * All speech now belongs to the overlay, which is also the honest arrangement: the suite is
 * supposed to exercise the agent exactly as the user does, and the user does not have a second
 * narrator.
 *
 * ### Sequencing
 *
 * Tasks are strictly sequential and never overlap. The screen is single mutable ground truth: two
 * agents driving one phone would produce traces that describe a device neither of them was
 * actually looking at.
 */
class EvalRunner(
    private val scope: CoroutineScope,
    private val store: EvalSuiteStore,
    /** Runs one goal through the agent and returns its closing sentence. Throws on hard failure. */
    private val runGoal: suspend (goal: String) -> String,
    /** Finds the trace a just-finished task produced, or null if it never wrote one. */
    private val findTrace: suspend (goal: String, startedAfterMillis: Long) -> SessionLog?,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Ceiling for a single task. The worst real baseline run took 235 s, and the expanded suite
     * adds slower categories (browser, resume), so the old 220 s laptop timeout would have cut off
     * exactly the tasks worth measuring.
     */
    private val taskTimeoutMs: Long = DEFAULT_TASK_TIMEOUT_MS,
    /**
     * Gap between tasks. Two quick tasks landing in the same rolling minute is the only way the
     * measured workload approaches 15 RPM / 250K TPM — no single task came close on its own.
     */
    private val interTaskDelayMs: Long = DEFAULT_INTER_TASK_DELAY_MS,
    /**
     * The host process that sets up and grades AndroidWorld tasks. Null is the ordinary case —
     * AURA's own 30 tasks never touch it — and a suite containing [EvalTask.awTask] rows without
     * one fails those tasks loudly rather than running them unprepared.
     */
    private val referee: EvalHostReferee? = null,
) {

    /** What the cockpit renders. Everything the screen needs, nothing it has to derive. */
    data class State(
        val runId: String? = null,
        val results: List<EvalTaskResult> = emptyList(),
        val phase: Phase = Phase.IDLE,
        val currentTaskNumber: Int? = null,
        /** Why the runner stopped, when it stopped itself. Shown as a banner. */
        val haltReason: String? = null,
        val haltedByRateLimit: Boolean = false,
        /**
         * Stopped because the task that just finished is waiting for a verdict. Distinct from the
         * other pauses: the user does not press Resume, they score the task and the sweep carries
         * on by itself.
         */
        val haltedForScoring: Boolean = false,
    ) {
        val tally: EvalTally get() = EvalTally.of(results)
    }

    enum class Phase {
        /** No suite open. */
        IDLE,

        /** A suite is open and nothing is executing. */
        READY,

        /** Working through the queue. */
        RUNNING,

        /**
         * Stopped, deliberately, with results intact. Either the user paused or a quota refusal
         * halted it. [State.haltedByRateLimit] says which — they need different next actions.
         */
        PAUSED,

        /** Every task has a verdict. */
        COMPLETE,
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * The queue the current sweep is working through, remembered so scoring a gated task can
     * continue it without the caller having to hand the task list back in.
     */
    private var lastQueue: List<EvalTask> = emptyList()

    /**
     * Stop after each task until it has been scored.
     *
     * On by default, and it is the difference between a measurement and a guess. The screen is
     * single mutable ground truth — "was audio actually playing?" cannot be recovered from a trace
     * ten minutes and four tasks later — so a sweep that runs all thirty back to back forces every
     * verdict to be entered from memory. Turning this off buys an unattended run at the cost of
     * scoring blind afterwards; that is a real trade, so it is a switch and not a rule.
     */
    @Volatile var gateOnScoring: Boolean = true
    /** Set from another coroutine to ask the loop to stop after the task in flight. */
    @Volatile private var pauseRequested = false

    // ── suite lifecycle ──────────────────────────────────────────────────────

    /** Open an existing run (e.g. after the app was killed mid-suite) without executing anything. */
    fun open(runId: String) {
        val results = store.readResults(runId)
        _state.value = State(
            runId = runId,
            results = results,
            phase = if (results.isNotEmpty() && results.all { it.isTerminal }) Phase.COMPLETE else Phase.READY,
        )
    }

    fun refresh() {
        val runId = _state.value.runId ?: return
        _state.value = _state.value.copy(results = store.readResults(runId))
    }

    // ── execution ────────────────────────────────────────────────────────────

    /**
     * Run every task that has not produced a result yet, in suite order.
     *
     * Deliberately skips tasks that already have a trace — including failed ones. A task that
     * genuinely failed has been measured; re-running it spends quota to learn something already
     * known. Use [runSingle] to retry one on purpose.
     */
    fun runRemaining(tasks: List<EvalTask>) {
        val runId = _state.value.runId ?: return
        if (job?.isActive == true) return

        pauseRequested = false
        _state.value = _state.value.copy(phase = Phase.RUNNING, haltReason = null, haltedByRateLimit = false)

        lastQueue = tasks
        job = scope.launch {
            val byNumber = tasks.associateBy { it.number }
            val queue = store.readResults(runId)
                .filter { it.status == EvalStatus.PENDING }
                .mapNotNull { byNumber[it.number] }

            for ((index, task) in queue.withIndex()) {
                if (!isActive || pauseRequested) {
                    halt("Paused after ${index} task(s).", rateLimited = false)
                    return@launch
                }
                val outcome = executeTask(runId, task)
                // One dead referee would otherwise write 116 identical "not run" rows in a few
                // seconds. Stop at the first, while the reason is still one line on the screen.
                if (outcome == Outcome.NOT_PREPARED) {
                    halt(
                        "Stopped at task ${task.number}: the AndroidWorld host did not set the " +
                            "task up, so nothing was run. Start scripts/bench/referee.py on the " +
                            "PC, then Resume.",
                        rateLimited = false,
                    )
                    return@launch
                }
                if (outcome == Outcome.RATE_LIMITED) {
                    halt(
                        "Stopped at task ${task.number}: the provider refused on quota. " +
                            "Swap in a fresh API key in Settings → Agent Brain, then Resume. " +
                            "Everything scored so far is kept.",
                        rateLimited = true,
                    )
                    return@launch
                }
                // Stop while the evidence is still on screen. Anything still unjudged after this
                // task ran — including one that errored — is worth a look before the next task
                // overwrites the phone.
                if (gateOnScoring && store.readResult(runId, task.number)?.verdict == null) {
                    halt(
                        "Task ${task.number} is waiting for your verdict. Score it and the suite " +
                            "continues on its own.",
                        rateLimited = false,
                        forScoring = true,
                    )
                    return@launch
                }
                if (index < queue.lastIndex) delay(interTaskDelayMs)
            }
            finish()
        }
    }

    /** Retry exactly one task, resetting it to PENDING first so its previous attempt is replaced. */
    fun runSingle(task: EvalTask) {
        val runId = _state.value.runId ?: return
        if (job?.isActive == true) return

        pauseRequested = false
        _state.value = _state.value.copy(phase = Phase.RUNNING, haltReason = null, haltedByRateLimit = false)

        job = scope.launch {
            val outcome = executeTask(runId, task)
            if (outcome == Outcome.RATE_LIMITED) {
                halt(
                    "Task ${task.number} hit the provider's quota limit. Swap the key and try again.",
                    rateLimited = true,
                )
            } else {
                _state.value = _state.value.copy(
                    phase = Phase.READY,
                    currentTaskNumber = null,
                    results = store.readResults(runId),
                )
            }
        }
    }

    /**
     * Ask the runner to stop after the task currently in flight.
     *
     * It deliberately does not cancel mid-task: the agent is halfway through touching a real phone,
     * and killing it there leaves the screen in a state the next task would inherit as a confound —
     * and the request it already paid for would be wasted.
     */
    fun pause() {
        if (_state.value.phase != Phase.RUNNING) return
        pauseRequested = true
        _state.value = _state.value.copy(haltReason = "Finishing the current task, then pausing…")
    }

    /** Stop immediately, abandoning the task in flight. For when the phone is visibly stuck. */
    fun abort() {
        pauseRequested = true
        job?.cancel()
        job = null
        // The cancel above is why this is needed: it kills the coroutine before executeTask can
        // reach its scoring call, and that call is what stops the host's camera and tears the
        // AndroidWorld task down. Without this, screenrecord keeps chunking to /sdcard and the
        // aborted task stays set up underneath whatever runs next.
        referee?.let { host -> scope.launch { host.abandon() } }
        halt("Aborted. The task in flight was not recorded.", rateLimited = false)
    }

    // ── scoring ──────────────────────────────────────────────────────────────

    /** Record the human's verdict. This is the gate metric; nothing else may write it. */
    fun score(number: Int, verdict: EvalVerdict, notes: String = "") {
        val runId = _state.value.runId ?: return
        store.updateResult(runId, number) {
            it.copy(status = EvalStatus.SCORED, verdict = verdict, notes = notes)
        }
        val results = store.readResults(runId)
        val wasGated = _state.value.haltedForScoring
        _state.value = _state.value.copy(
            results = results,
            haltedForScoring = false,
            haltReason = if (wasGated) null else _state.value.haltReason,
            phase = when {
                _state.value.phase == Phase.RUNNING -> Phase.RUNNING
                results.isNotEmpty() && results.all { it.isTerminal } -> Phase.COMPLETE
                else -> _state.value.phase
            },
        )
        // The gate is not a pause the user has to undo — scoring IS the "continue" gesture. Making
        // them tap Resume as well would add a second button press to every one of thirty tasks.
        if (wasGated && _state.value.phase != Phase.COMPLETE && lastQueue.isNotEmpty()) {
            runRemaining(lastQueue)
        }
    }

    /**
     * Persist the human's note without touching the verdict.
     *
     * Separate from [score] because the two happen in the other order than the API suggested: you
     * decide pass/fail first and explain it afterwards. Folding notes into scoring meant anything
     * typed after the verdict was dropped.
     */
    fun saveNotes(number: Int, notes: String) {
        val runId = _state.value.runId ?: return
        store.updateResult(runId, number) { it.copy(notes = notes) }
        refresh()
    }

    /** Undo a verdict, putting the task back in the queue of things to look at. */
    fun clearScore(number: Int) {
        val runId = _state.value.runId ?: return
        store.updateResult(runId, number) {
            it.copy(
                status = if (it.sessionId != null || it.attempts > 0) EvalStatus.AWAITING_SCORE else EvalStatus.PENDING,
                verdict = null,
            )
        }
        refresh()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private enum class Outcome { FINISHED, RATE_LIMITED, ERRORED, NOT_PREPARED }

    private suspend fun executeTask(runId: String, task: EvalTask): Outcome {
        val startedAt = now()
        store.updateResult(runId, task.number) {
            it.copy(
                status = EvalStatus.RUNNING,
                startedAtMillis = startedAt,
                endedAtMillis = null,
                attempts = it.attempts + 1,
                verdict = null,
                rateLimited = false,
                outcome = "",
            )
        }
        _state.value = _state.value.copy(
            currentTaskNumber = task.number,
            results = store.readResults(runId),
        )

        // An AndroidWorld task is not runnable until the host has seeded the world, and the goal
        // does not exist until then either — it is generated from the run seed. See
        // [EvalHostReferee] for why a failed setup must stop the task rather than let it run.
        val goal = if (task.awTask == null) task.goal else {
            val prepared = runCatching { checkNotNull(referee) { "no host referee is attached" }.prepare(task) }
            prepared.getOrElse { err ->
                abandonUnprepared(runId, task, err)
                return Outcome.NOT_PREPARED
            }
        }
        // Persisted because the goal is the join key to the trace and to `agent_eval.py`. Leaving
        // the placeholder on disk would make every AndroidWorld row unjoinable after the fact.
        if (goal != task.goal) store.updateResult(runId, task.number) { it.copy(goal = goal) }

        var thrown: Throwable? = null
        val answer: String? = try {
            withTimeoutOrNull(taskTimeoutMs) { runGoal(goal) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            thrown = t
            null
        }

        val trace = runCatching { findTrace(goal, startedAt) }.getOrNull()
        val rateLimited = detectRateLimit(thrown, trace)

        // Asked even after a throw or a timeout. The host still has a camera rolling and a task to
        // dismantle, and "what does the phone look like now" is a fair question after a timeout —
        // a run that never reached the right app simply scores zero.
        val refereed: Pair<EvalVerdict, String>? = task.awTask?.let {
            runCatching { checkNotNull(referee).score(task) }.getOrElse { err ->
                Log.w(TAG, "referee could not score $it: ${err.message}")
                EvalVerdict.INVALID to "AndroidWorld could not score this run: ${err.message}"
            }
        }

        val finished = store.updateResult(runId, task.number) { current ->
            current.copy(
                status = when {
                    // Both of these carry a verdict already, so there is nothing for a human to judge.
                    rateLimited -> EvalStatus.SCORED
                    refereed != null -> EvalStatus.SCORED
                    thrown != null -> EvalStatus.ERROR
                    answer == null -> EvalStatus.ERROR
                    else -> EvalStatus.AWAITING_SCORE
                },
                // A quota refusal outranks the referee's verdict: AndroidWorld would score an agent
                // that never got to call its model as a plain failure, which is the harness's fault
                // being recorded as the agent's.
                verdict = if (rateLimited) EvalVerdict.INVALID else refereed?.first,
                notes = when {
                    rateLimited -> "Provider quota refusal — not an agent failure."
                    refereed != null -> refereed.second
                    else -> current.notes
                },
                sessionId = trace?.sessionId ?: current.sessionId,
                endedAtMillis = now(),
                rateLimited = rateLimited,
                outcome = when {
                    rateLimited -> QuotaExhaustion.spokenReasonFor(thrown)
                        ?: "The provider refused the request on quota."
                    // A cancelled run and an exhausted timeout are different facts about
                    // different problems, and the file used to record both as the second one.
                    // The elapsed figure is the tell: "cancelled after 4s" points at the run,
                    // "cancelled after 300s" points at whatever was watching it.
                    thrown is AgentRunBridge.RunCancelled ->
                        "Cancelled after ${(now() - startedAt) / 1000}s — the run was stopped " +
                            "before it answered. Not a timeout, and not the agent failing."
                    thrown != null -> "Threw: ${thrown.message ?: thrown::class.simpleName}"
                    answer == null -> "Timed out after ${taskTimeoutMs / 1000}s."
                    else -> answer
                },
            )
        }

        _state.value = _state.value.copy(
            currentTaskNumber = null,
            results = store.readResults(runId),
        )
        Log.i(TAG, "task ${task.number} -> ${finished?.status} rateLimited=$rateLimited")

        return when {
            rateLimited -> Outcome.RATE_LIMITED
            thrown != null || answer == null -> Outcome.ERRORED
            else -> Outcome.FINISHED
        }
    }

    /**
     * Record that an AndroidWorld task never started, and why.
     *
     * Deliberately [EvalStatus.ERROR] with no verdict rather than a fail. Nothing was learned about
     * the agent: the world was never put into the state the task describes. A published benchmark
     * that counts "the referee was not running" as an agent failure is worse than one with a gap.
     */
    private fun abandonUnprepared(runId: String, task: EvalTask, err: Throwable) {
        store.updateResult(runId, task.number) {
            it.copy(
                status = EvalStatus.ERROR,
                endedAtMillis = now(),
                outcome = "Not run — AndroidWorld setup failed: ${err.message ?: err::class.simpleName}",
            )
        }
        _state.value = _state.value.copy(
            currentTaskNumber = null,
            results = store.readResults(runId),
        )
        Log.w(TAG, "task ${task.number} (${task.awTask}) not prepared: ${err.message}")
    }

    /**
     * Two independent signals, because either alone misses real cases.
     *
     * The throwable catches a run that died on a 429 the retry ladder could not outlast. The trace
     * catches the quieter shape: the agent absorbed the refusals, ended "successfully" with nothing
     * accomplished, and would otherwise be scored a genuine failure by a human who never saw why.
     */
    private fun detectRateLimit(thrown: Throwable?, trace: SessionLog?): Boolean {
        if (QuotaExhaustion.spokenReasonFor(thrown) != null) return true
        val calls = trace?.llmCalls ?: return false
        return calls.any { QuotaExhaustion.isQuotaFailure(it.response) }
    }

    private fun halt(reason: String, rateLimited: Boolean, forScoring: Boolean = false) {
        val runId = _state.value.runId
        _state.value = _state.value.copy(
            phase = Phase.PAUSED,
            currentTaskNumber = null,
            haltReason = reason,
            haltedByRateLimit = rateLimited,
            haltedForScoring = forScoring,
            results = runId?.let { store.readResults(it) } ?: _state.value.results,
        )
    }

    private fun finish() {
        val runId = _state.value.runId ?: return
        val results = store.readResults(runId)
        _state.value = _state.value.copy(
            phase = if (results.all { it.isTerminal }) Phase.COMPLETE else Phase.READY,
            currentTaskNumber = null,
            results = results,
            haltReason = null,
            haltedByRateLimit = false,
            haltedForScoring = false,
        )
    }

    companion object {
        private const val TAG = "EvalRunner"

        /** 6 minutes: the worst measured run was 235 s and the slower categories are not yet measured. */
        const val DEFAULT_TASK_TIMEOUT_MS = 360_000L

        /**
         * 45 s. Chosen against the measured workload, not a guess: no single task in the 2026-08-13
         * baseline breached 15 RPM or 250K TPM on its own (worst was 8 calls / 141K tokens), so the
         * only real exposure is two short tasks sharing a rolling minute. A gap slightly under a
         * minute removes that without adding an hour to a 40-task sitting.
         */
        const val DEFAULT_INTER_TASK_DELAY_MS = 45_000L
    }
}
