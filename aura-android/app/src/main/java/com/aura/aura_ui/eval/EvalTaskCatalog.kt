package com.aura.aura_ui.eval

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads the eval suite definition from `assets/eval/tasks.json`.
 *
 * ### Why the JSON is the source and the markdown is generated
 *
 * The suite has three readers — this app, `scripts/agent_eval.py`, and a human reading
 * `docs/agent-review/EVAL_TASKS.md`. Before the cockpit existed the markdown was the source and
 * the other two parsed it with regexes. Adding a third parser (on a different platform, with a
 * different markdown dialect) to the same hand-edited table is how a goal string quietly stops
 * matching and every session joins nothing.
 *
 * So the machine-readable file is now the source and the markdown is rendered from it by
 * `scripts/gen_eval_tasks_md.py`. The goal string is the join key between a task and its trace,
 * and it now exists in exactly one place.
 *
 * ### The apostrophe rule survives
 *
 * `run_eval_suite.ps1` still exists for headless runs and single-quotes each goal for the device
 * shell, so an apostrophe in a goal breaks it. [validate] enforces that here rather than leaving
 * it to a shell error three layers away.
 */
object EvalTaskCatalog {

    private const val TAG = "EvalTaskCatalog"
    const val ASSET_PATH = "eval/tasks.json"

    /**
     * Google's AndroidWorld suite, kept in its own file rather than merged into [ASSET_PATH].
     *
     * Two different contracts live in these files and only one of them is ours. `tasks.json`'s
     * goals are frozen because *we* chose them and a trace is matched by goal text; AndroidWorld's
     * are generated per seed at setup and the rows here are placeholders. Putting both under one
     * "goal is frozen" header would document a rule that half the file breaks.
     *
     * Numbers start at 1001 so the two suites can never collide in a run directory
     * (`task-001.json` is a filename, and [EvalTask.number] is a permanent join key).
     */
    const val ANDROID_WORLD_ASSET_PATH = "eval/androidworld.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Suite(val version: Int = 1, val tasks: List<EvalTask> = emptyList())

    /**
     * Read the bundled suite. Returns an empty list rather than throwing: a missing or malformed
     * asset must leave the cockpit showing "no tasks" with a log line, not crash the app on a
     * screen the user opened to diagnose something else.
     */
    fun load(context: Context): List<EvalTask> =
        (readSuite(context, ASSET_PATH) + readSuite(context, ANDROID_WORLD_ASSET_PATH))
            .sortedWith(compareBy({ EvalCategory.orderOf(it.category) }, { it.number }))

    /** AURA's own suite — the balanced 30 that gate a change. */
    fun auraSuite(tasks: List<EvalTask>): List<EvalTask> = tasks.filter { it.awTask == null }

    /** The AndroidWorld 116. Needs `scripts/bench/referee.py` running on the PC. */
    fun androidWorldSuite(tasks: List<EvalTask>): List<EvalTask> = tasks.filter { it.awTask != null }

    private fun readSuite(context: Context, path: String): List<EvalTask> =
        runCatching {
            val text = context.applicationContext.assets.open(path)
                .bufferedReader().use { it.readText() }
            json.decodeFromString<Suite>(text).tasks
        }.onFailure { Log.e(TAG, "could not read $path: ${it.message}", it) }
            .getOrDefault(emptyList())

    /**
     * Structural problems that would corrupt a measurement rather than merely look untidy.
     * Surfaced in the cockpit before a run rather than discovered halfway through one.
     */
    fun validate(tasks: List<EvalTask>): List<String> {
        val problems = mutableListOf<String>()

        tasks.groupBy { it.number }.filterValues { it.size > 1 }.keys.sorted().forEach {
            problems += "task $it is defined more than once — the number is a join key"
        }
        tasks.groupBy { normalizeGoal(it.goal) }.filterValues { it.size > 1 }.forEach { (_, dupes) ->
            problems += "tasks ${dupes.map { it.number }} share a goal string — traces cannot be told apart"
        }
        tasks.filter { it.goal.contains('\'') }.forEach {
            problems += "task ${it.number}: goal contains an apostrophe, which breaks device-shell quoting"
        }
        tasks.filter { it.truthCheck.isBlank() }.forEach {
            problems += "task ${it.number}: no truth check — nothing to score it against"
        }
        tasks.filter { EvalCategory.byId(it.category) == null }.forEach {
            problems += "task ${it.number}: unknown category '${it.category}'"
        }
        return problems
    }

    /**
     * Categories that do not yet have enough tasks to speak about their plane.
     * Not an error — a thin suite is a deliberate, cheap first baseline — but the cockpit says so,
     * because a number from an under-covered category reads exactly like a real one.
     */
    fun coverageGaps(tasks: List<EvalTask>): List<String> {
        val counts = tasks.groupingBy { it.category }.eachCount()
        return EvalCategory.entries.mapNotNull { cat ->
            val have = counts[cat.id] ?: 0
            if (have >= cat.floor) null else "${cat.label}: $have of ${cat.floor}"
        }
    }

    /** Matches `agent_eval.py`'s `_norm` so the app and the aggregator join identically. */
    fun normalizeGoal(goal: String): String = goal.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
