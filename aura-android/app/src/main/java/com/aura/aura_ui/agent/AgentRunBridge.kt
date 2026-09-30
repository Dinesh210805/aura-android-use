package com.aura.aura_ui.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

/**
 * Announces the end of an agent run started through the overlay, so something that is not the
 * overlay can wait for it.
 *
 * ### Why this exists
 *
 * The eval cockpit used to call `AuraAgent.runFromSavedSettings` itself. That works, and it is
 * wrong: it runs the agent through a path the user never uses. No pill, no THINKING phase, no
 * spoken reply, no conversation entries, and — the part that actually matters for a measurement —
 * none of `executeAgent`'s control-lock handling, so a suite could keep issuing tasks straight past
 * a human pause. A harness that measures a private code path is not measuring the product.
 *
 * The fix is for the cockpit to ask the overlay to run the task exactly as a typed command, which
 * means it needs a way to know when the run finished and what it said. `startService` is
 * fire-and-forget, so the answer comes back here.
 *
 * ### Why a SharedFlow and not a callback
 *
 * A run can end while nobody is listening (the user drove it by voice), and that must not queue up
 * a stale result for the next listener. Replay is zero for exactly that reason: a subscriber gets
 * runs that finish *after* it starts waiting, never one from before.
 */
object AgentRunBridge {

    /** What one finished run produced. [error] is set when the run threw rather than replied. */
    data class Outcome(
        val goal: String,
        val reply: String,
        val error: Throwable? = null,
    )

    /**
     * A run that was stopped before it could answer — dismissed, killed by the control lock, or
     * cancelled by whatever else owns its scope.
     *
     * Deliberately **not** a [kotlinx.coroutines.CancellationException]. A waiter that receives one
     * of those is obliged to treat its own scope as cancelled and unwind, which for the eval
     * cockpit would abort the whole suite instead of failing one task. This is ordinary bad news
     * about someone else's run, and it must be catchable as such.
     *
     * Found 2026-08-26: two suite tasks whose runs were cancelled at 4.4 s and 7.2 s were recorded
     * as "Timed out after 360s", because cancellation unwound the publisher and the waiter had
     * nothing to hear. Six minutes of a human's sitting, twice, and a file that then lied about
     * what happened.
     */
    class RunCancelled(val goal: String) : RuntimeException(
        "The run for \"$goal\" was cancelled before it finished.",
    )

    /**
     * Announce a run that was cancelled rather than completed.
     *
     * Callers must invoke this from a `NonCancellable` context: by the time a run is cancelling,
     * a plain [publish] would be cancelled along with it — which is exactly the bug this exists
     * to close.
     */
    suspend fun publishCancelled(goal: String) {
        publish(Outcome(goal = goal, reply = "", error = RunCancelled(goal)))
    }

    private val _outcomes = MutableSharedFlow<Outcome>(
        replay = 0,
        // A little slack so a burst cannot drop an outcome a waiter is about to consume; the
        // suspending emit below is the real guarantee.
        extraBufferCapacity = 8,
    )
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    /** Called by the overlay when a run ends, whether it replied or threw. */
    suspend fun publish(outcome: Outcome) {
        _outcomes.emit(outcome)
    }

    /**
     * Wait for the next run of [goal] to finish.
     *
     * Matching on the goal text — the same normalisation the trace join uses — rather than taking
     * whichever run finishes next, so a task the user starts by voice mid-suite cannot be mistaken
     * for the suite's own.
     */
    suspend fun awaitOutcome(goal: String): Outcome {
        val wanted = normalize(goal)
        return outcomes.first { normalize(it.goal) == wanted }
    }

    /**
     * Suspend until at least one collector is attached.
     *
     * Without this the caller races its own listener: `startService` can deliver, run, and finish a
     * trivial task before `outcomes.first { }` has subscribed, and with replay = 0 that outcome is
     * gone — the suite would then hang on a task that already succeeded.
     */
    suspend fun awaitSubscriber() {
        _outcomes.subscriptionCount.first { it > 0 }
    }

    private fun normalize(text: String): String =
        text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
