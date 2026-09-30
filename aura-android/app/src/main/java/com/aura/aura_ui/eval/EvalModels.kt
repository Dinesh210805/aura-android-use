package com.aura.aura_ui.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The eval cockpit's data model.
 *
 * ### Why this exists on the device at all
 *
 * The suite used to be driven from the laptop: `run_eval_suite.ps1` broadcast a goal, slept, and
 * scraped logcat. That works right up to the moment a provider rate-limits mid-suite — the script
 * has no idea, keeps firing goals at an agent that cannot call its model, and burns the rest of a
 * free tier's daily request budget writing failures. Recovering meant re-running the *whole* suite
 * the next day.
 *
 * On-device the runner can see a rate limit the instant it happens, stop, keep every result it has
 * already earned, and let the user swap keys and resume from the task that failed. Under a free
 * tier that is not a convenience — 500 requests/day against a suite that costs ~300 means there is
 * no second attempt, so losing a half-finished run loses the day.
 *
 * The verdict vocabulary is deliberately identical to `scripts/eval_truth_checks.md`, because
 * `scripts/agent_eval.py` aggregates what this writes. See [EvalVerdict] for why `REFUSED` and
 * `INVALID` are two words and not one.
 */
@Serializable
data class EvalTask(
    /** Stable join key. Never renumber a task — old suite runs reference it. */
    val number: Int,
    /** The exact sentence given to the agent. Frozen once used: it identifies the task in traces. */
    val goal: String,
    /** One of [EvalCategory]'s ids. Kept as a string so an unknown category degrades, not crashes. */
    val category: String,
    /** What the human looks for on screen to decide pass/fail. Shown next to the scoring buttons. */
    @SerialName("truth_check") val truthCheck: String,
    /**
     * True when the task needs the human to *do* something mid-run — answer AURA's question,
     * pause it, or type into a field. The runner surfaces these so a hands-off batch can skip
     * them and still finish unattended.
     */
    @SerialName("needs_human") val needsHuman: Boolean = false,
    /**
     * The AndroidWorld task class this row stands for, e.g. `MarkorCreateNote`. Null for every
     * task in AURA's own suite.
     *
     * Non-null inverts three things about the lifecycle, which is why it is a field and not a
     * category check:
     *
     *  - **The goal is not ours.** AndroidWorld generates the sentence at setup time from the run
     *    seed ("Create a note named `2023_10_15_wistful_dawn.md`"), so it cannot be frozen in
     *    `androidworld.json` the way [goal] is for the AURA suite. [goal] holds a readable
     *    placeholder and the real sentence arrives from the host referee.
     *  - **Setup happens off-device.** The referee force-stops apps, seeds their SQLite, and pins
     *    the clock before the agent is allowed to see the screen.
     *  - **Scoring is automatic.** `is_successful(env)` inspects device state afterwards, so these
     *    tasks never reach [EvalStatus.AWAITING_SCORE].
     */
    @SerialName("aw_task") val awTask: String? = null,
)

/**
 * The suite's coverage categories. One category cannot detect a regression in another, which is
 * the whole reason the suite is not simply "ten tasks that felt representative".
 *
 * `floor` is the minimum number of tasks that must exist for the suite to be able to speak about
 * that category at all; it mirrors the table in `docs/agent-review/EVAL_TASKS.md`.
 */
enum class EvalCategory(val id: String, val label: String, val floor: Int) {
    DETERMINISTIC("deterministic", "Deterministic", 3),
    IN_APP("in-app", "In-app flow", 5),
    LOOK("look", "Look / read", 5),
    BROWSER("browser", "Browser", 5),
    ASK("ask", "Asks the user", 3),
    REFUSE("refuse", "Must refuse", 2),
    RESUME("resume", "Pause + resume", 3),
    KEYBOARD("keyboard", "AURA keyboard", 2),
    PICKER("picker", "Wheel / date picker", 2),

    /**
     * Google's AndroidWorld suite, driven through the host referee.
     *
     * Floor 0 on purpose. The floors above exist because AURA's suite is *balanced* — one category
     * cannot detect a regression in another, so a thin category is a blind spot worth naming.
     * AndroidWorld is not a plane of that suite, it is a separate benchmark with its own 116-task
     * composition that we do not get to choose. Giving it a floor would report a coverage gap the
     * user has no way to close.
     */
    ANDROID_WORLD("androidworld", "AndroidWorld", 0),
    ;

    companion object {
        fun byId(id: String): EvalCategory? = entries.firstOrNull { it.id == id }

        /** Category order for display and for folder listing, so a suite always reads the same way. */
        fun orderOf(id: String): Int = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
}

/**
 * The human's verdict. Four words, and the difference between two of them is load-bearing.
 *
 * [REFUSED] and [INVALID] used to be a single verdict that left the success denominator. That made
 * every `refuse`-category task unwinnable: a correct policy block is the *pass* condition for those
 * tasks, so scoring it as "excluded" meant the task could only ever cost points and never earn any.
 * They are now opposites:
 *
 *  - [REFUSED] — the agent was asked to do something it should not, and declined. **Counts as a pass.**
 *  - [INVALID] — the world was not in a state where the task could be attempted (no contact, app
 *    logged out, provider rate-limited). The trial never tested the agent. **Excluded.**
 *
 * A rate-limited run is [INVALID], not [FAIL]: running out of quota is the harness failing, not the
 * agent. The runner sets that verdict itself so a tired human never has to remember the rule.
 */
@Serializable
enum class EvalVerdict(val wire: String) {
    PASS("pass"),
    FAIL("fail"),
    REFUSED("refused"),
    INVALID("invalid"),
    ;

    /** True when this verdict counts toward verified success (see `agent_eval.py`'s `aggregate`). */
    val countsAsSuccess: Boolean get() = this == PASS || this == REFUSED

    /** True when this verdict leaves the denominator entirely. */
    val excluded: Boolean get() = this == INVALID
}

/** Where a task is in its lifecycle, independent of what the human thought of the result. */
@Serializable
enum class EvalStatus {
    /** Never attempted in this suite run. */
    PENDING,

    /** The agent is working on it right now. Exactly one task may be in this state. */
    RUNNING,

    /** The agent finished. Waiting for the human to say whether the world actually changed. */
    AWAITING_SCORE,

    /** Scored. [EvalTaskResult.verdict] is non-null. */
    SCORED,

    /**
     * The run threw, timed out, or the provider refused. Distinct from a `fail` verdict: nothing
     * about the agent's *ability* was learned here.
     */
    ERROR,
}

/**
 * One task's outcome inside one suite run. This is the unit that gets written to disk, so it is
 * self-describing: someone reading a single file six weeks later should not need the manifest to
 * understand what they are looking at.
 */
@Serializable
data class EvalTaskResult(
    val number: Int,
    val goal: String,
    val category: String,
    @SerialName("truth_check") val truthCheck: String = "",
    val status: EvalStatus = EvalStatus.PENDING,
    val verdict: EvalVerdict? = null,
    /** Free-text note from the human — why it failed, what was odd. Optional but valuable later. */
    val notes: String = "",
    /**
     * The agent trace this task produced, in `filesDir/mcp_logs/<sessionId>`. Null when the run
     * never got far enough to write one. The trace is referenced, never copied: duplicating a
     * multi-megabyte screenshot set per suite run would fill the device.
     */
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("started_at_millis") val startedAtMillis: Long? = null,
    @SerialName("ended_at_millis") val endedAtMillis: Long? = null,
    /** The agent's own closing sentence, or the exception message when [status] is ERROR. */
    val outcome: String = "",
    /**
     * True when this attempt died on a provider rate/quota refusal. Kept as a flag rather than
     * inferred from [outcome] later, because it is what makes the run resumable: these are the
     * tasks to retry after swapping keys, and they must never be read as agent failures.
     */
    @SerialName("rate_limited") val rateLimited: Boolean = false,
    /** How many times this task has been attempted in this suite run. */
    val attempts: Int = 0,
) {
    val isTerminal: Boolean get() = status == EvalStatus.SCORED
    val needsAttention: Boolean get() = status == EvalStatus.AWAITING_SCORE || status == EvalStatus.ERROR
}

/**
 * The header for one sitting of the suite. Written once when the run is created and updated as it
 * progresses, so an interrupted run can be reopened and continued rather than restarted.
 *
 * [buildVersionCode] and [model] are recorded because a score is meaningless without them: the
 * whole point of a baseline is to say "this code, this model, this number", and a comparison across
 * two different builds is not a comparison at all.
 */
@Serializable
data class EvalSuiteRun(
    val id: String,
    @SerialName("started_at_millis") val startedAtMillis: Long,
    @SerialName("ended_at_millis") val endedAtMillis: Long? = null,
    val label: String = "",
    @SerialName("build_version_code") val buildVersionCode: Int = 0,
    @SerialName("build_version_name") val buildVersionName: String = "",
    val provider: String = "",
    val model: String = "",
    @SerialName("task_count") val taskCount: Int = 0,
)

/**
 * The aggregate the cockpit shows live and `agent_eval.py` recomputes from disk.
 *
 * Two success numbers on purpose. `claimed` is what the agent said about itself and it has lied in
 * production; `verified` is the human's verdict and is the only one that gates a change. Their
 * disagreement is itself the metric — see [falseSuccesses].
 */
data class EvalTally(
    val total: Int = 0,
    val scored: Int = 0,
    val judged: Int = 0,
    val passed: Int = 0,
    val failed: Int = 0,
    val refused: Int = 0,
    val invalid: Int = 0,
    val errored: Int = 0,
    val awaitingScore: Int = 0,
    val pending: Int = 0,
    val rateLimited: Int = 0,
    /**
     * Refusals scored on a task that is not in the `refuse` category — a free pass handed to the
     * agent for declining work it should have done. Expected to be 0; surfaced so it stays visible
     * rather than quietly inflating the rate.
     */
    val refusedOffCategory: Int = 0,
) {
    /** Verified success rate, or null when nothing has been judged yet. Never guess a gate metric. */
    val verifiedSuccessRate: Double? get() = if (judged == 0) null else passed.plus(refused) / judged.toDouble()

    val remaining: Int get() = pending + awaitingScore + errored

    companion object {
        fun of(results: Collection<EvalTaskResult>): EvalTally {
            val scored = results.filter { it.verdict != null }
            val judged = scored.filterNot { it.verdict!!.excluded }
            return EvalTally(
                total = results.size,
                scored = scored.size,
                judged = judged.size,
                passed = judged.count { it.verdict == EvalVerdict.PASS },
                failed = judged.count { it.verdict == EvalVerdict.FAIL },
                refused = scored.count { it.verdict == EvalVerdict.REFUSED },
                invalid = scored.count { it.verdict == EvalVerdict.INVALID },
                errored = results.count { it.status == EvalStatus.ERROR },
                awaitingScore = results.count { it.status == EvalStatus.AWAITING_SCORE },
                pending = results.count { it.status == EvalStatus.PENDING },
                rateLimited = results.count { it.rateLimited },
                refusedOffCategory = scored.count {
                    it.verdict == EvalVerdict.REFUSED && it.category != EvalCategory.REFUSE.id
                },
            )
        }
    }
}
