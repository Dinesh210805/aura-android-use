package com.aura.aura_ui.eval

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import android.content.Intent
import com.aura.aura_ui.MainActivity
import com.aura.aura_ui.agent.AgentRunBridge
import com.aura.aura_ui.overlay.AuraOverlayService
import com.aura.aura_ui.mcp.log.McpSessionStore
import com.aura.aura_ui.mcp.log.SessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Process-scoped owner of the eval cockpit's runner.
 *
 * ### Why a singleton and not a ViewModel
 *
 * A ViewModel survives rotation. It does not survive the Activity going away — and this Activity
 * *always* goes away: the agent's whole job is to drive **other** apps, so within seconds of
 * starting a sweep the cockpit is backgrounded behind YouTube or WhatsApp for the next hour. State
 * scoped to the screen would be torn down at exactly the moment the suite starts working.
 *
 * Process scope is the right lifetime here because it matches what is actually running: the agent
 * lives in the app process, `AssistantForegroundService` keeps that process alive, and the suite
 * ends when the process does. The UI attaches to this and observes; it never owns it.
 *
 * Everything durable is on disk after every task ([EvalSuiteStore] writes on each mutation), so even
 * process death only loses the task in flight — reopen the run and continue.
 */
object EvalController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var store: EvalSuiteStore? = null
    private var sessions: McpSessionStore? = null
    private var runner: EvalRunner? = null

    private val _tasks = MutableStateFlow<List<EvalTask>>(emptyList())
    val tasks: StateFlow<List<EvalTask>> = _tasks.asStateFlow()

    private val _runs = MutableStateFlow<List<EvalSuiteRun>>(emptyList())
    val runs: StateFlow<List<EvalSuiteRun>> = _runs.asStateFlow()

    private val _state = MutableStateFlow(EvalRunner.State())
    val state: StateFlow<EvalRunner.State> = _state.asStateFlow()

    /** Idempotent. Safe to call from every composition of the screen. */
    fun attach(context: Context) {
        val app = context.applicationContext
        if (store == null) {
            store = EvalSuiteStore(app)
            sessions = McpSessionStore(app)
        }
        if (_tasks.value.isEmpty()) _tasks.value = EvalTaskCatalog.load(app)
        _runs.value = store!!.listRuns()

        if (runner == null) {
            val agentStore = store!!
            runner = EvalRunner(
                scope = scope,
                store = agentStore,
                runGoal = { goal -> runThroughOverlay(app, goal) },
                findTrace = { goal, startedAfter -> findTrace(goal, startedAfter) },
                // Always attached, never always used: only rows carrying an `aw_task` reach it, so
                // an AURA-suite sitting behaves exactly as it did before and needs nothing running
                // on a PC.
                referee = EvalHostReferee(),
            ).also { r ->
                scope.launch {
                    var wasGated = false
                    r.state.collect { st ->
                        _state.value = st
                        // The moment the runner starts waiting for a verdict, put the cockpit in
                        // front of the user. It is buried behind whatever app the agent was just
                        // driving, so otherwise the "score me" state is invisible and the sweep
                        // looks like it silently stopped. Edge-triggered: re-launching the activity
                        // on every state emission would fight the user trying to leave it.
                        if (st.haltedForScoring && !wasGated) bringCockpitToFront(app)
                        wasGated = st.haltedForScoring
                    }
                }
            }
        }
    }

    // ── suite lifecycle ──────────────────────────────────────────────────────

    /**
     * @param androidWorld seed this sitting with Google's 116-task AndroidWorld suite instead of
     *   AURA's own 30. The two are never mixed in one run: they are graded by different referees
     *   (a human vs. `is_successful(env)`), so one success rate over both would be two numbers
     *   averaged into a third that means nothing.
     */
    fun createRun(context: Context, label: String, androidWorld: Boolean = false): String? {
        val s = store ?: return null
        val app = context.applicationContext
        val id = EvalSuiteStore.newRunId(System.currentTimeMillis(), label)
        val (versionCode, versionName) = buildInfo(app)
        val suite = if (androidWorld) {
            EvalTaskCatalog.androidWorldSuite(_tasks.value)
        } else {
            EvalTaskCatalog.auraSuite(_tasks.value)
        }
        s.createRun(
            EvalSuiteRun(
                id = id,
                startedAtMillis = System.currentTimeMillis(),
                label = label,
                buildVersionCode = versionCode,
                buildVersionName = versionName,
            ),
            suite,
        )
        _runs.value = s.listRuns()
        runner?.open(id)
        return id
    }

    fun open(runId: String) {
        runner?.open(runId)
    }

    fun deleteRun(runId: String) {
        store?.deleteRun(runId)
        _runs.value = store?.listRuns().orEmpty()
        if (_state.value.runId == runId) _state.value = EvalRunner.State()
    }

    fun exportCurrent(): String? = _state.value.runId?.let { store?.exportRun(it)?.absolutePath }

    /** Every sitting on the device in one file — see [EvalSuiteStore.exportAll]. */
    fun exportEverything(): String? = store?.exportAll()?.absolutePath

    // ── execution ────────────────────────────────────────────────────────────

    /**
     * @param skipNeedsHuman leave the tasks that require someone at the phone for a later sitting.
     *   The suite is deliberately mixed, and being able to run the 22 hands-off tasks unattended is
     *   what makes a full sitting affordable.
     */
    fun runAll(skipNeedsHuman: Boolean) {
        val queue = if (skipNeedsHuman) _tasks.value.filterNot { it.needsHuman } else _tasks.value
        runner?.runRemaining(queue)
    }

    /** See [EvalRunner.gateOnScoring] — stop after each task until it has a verdict. */
    var gateOnScoring: Boolean
        get() = runner?.gateOnScoring ?: true
        set(value) { runner?.gateOnScoring = value }

    fun runOne(task: EvalTask) = runner?.runSingle(task)
    fun pause() = runner?.pause()
    fun abort() = runner?.abort()
    fun score(number: Int, verdict: EvalVerdict, notes: String) = runner?.score(number, verdict, notes)
    fun saveNotes(number: Int, notes: String) = runner?.saveNotes(number, notes)
    fun clearScore(number: Int) = runner?.clearScore(number)
    fun refresh() = runner?.refresh()

    /** The raw trace for a finished task, rendered for the detail sheet. */
    suspend fun traceJson(sessionId: String): String? = withContext(Dispatchers.IO) {
        sessions?.sessionDir(sessionId)?.resolve("metadata.json")?.takeIf { it.isFile }?.readText()
    }

    /**
     * Run one task the way the user does: hand it to the overlay as a typed command.
     *
     * The cockpit used to call `AuraAgent.runFromSavedSettings` directly, which is a path no user
     * ever takes — no pill, no spoken reply, and none of `executeAgent`'s control-lock handling, so
     * a sweep could march straight past a human pause. Measuring a private code path tells you
     * nothing about the product.
     *
     * The subscriber wait is load-bearing: `startService` is fire-and-forget, and a trivial task
     * can finish before the collector attaches. With replay = 0 that outcome would be gone and the
     * suite would hang on a task that had already succeeded.
     */
    private suspend fun runThroughOverlay(context: Context, goal: String): String = coroutineScope {
        val waiter = async { AgentRunBridge.awaitOutcome(goal) }
        AgentRunBridge.awaitSubscriber()
        context.startService(
            Intent(context, AuraOverlayService::class.java)
                .setAction(AuraOverlayService.ACTION_RUN_TASK)
                .putExtra(AuraOverlayService.EXTRA_TASK_TEXT, goal),
        )
        val outcome = waiter.await()
        outcome.error?.let { throw it }
        outcome.reply
    }

    /**
     * Surface the cockpit so the task that just ran can be scored.
     *
     * Hides the overlay first — it is a floating window and would otherwise sit on top of the
     * verdict buttons. Safe to do here: the task has finished, so nothing is being interrupted.
     */
    private fun bringCockpitToFront(context: Context) {
        runCatching {
            context.startService(
                Intent(context, AuraOverlayService::class.java).setAction(AuraOverlayService.ACTION_HIDE),
            )
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_NAVIGATE_TO_EVAL, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Match a finished run to its trace by goal text and start time.
     *
     * The goal is the same join key `agent_eval.py` uses, and the time bound stops a repeat task
     * (tasks 1 and 5 are near-duplicates on purpose) from picking up an earlier run of the same
     * goal. Newest-first so a retry claims its own attempt rather than the one it replaced.
     */
    private suspend fun findTrace(goal: String, startedAfterMillis: Long): SessionLog? =
        withContext(Dispatchers.IO) {
            val wanted = EvalTaskCatalog.normalizeGoal(goal)
            sessions?.listSessions()
                ?.asSequence()
                ?.filter { it.source == "agent" }
                ?.filter { it.startedAtMillis >= startedAfterMillis - TRACE_CLOCK_SLACK_MS }
                ?.filter { EvalTaskCatalog.normalizeGoal(it.command.orEmpty()) == wanted }
                ?.maxByOrNull { it.startedAtMillis }
        }

    private fun buildInfo(context: Context): Pair<Int, String> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
        code to (info.versionName ?: "")
    }.getOrElse { _: Throwable -> 0 to "" }

    /**
     * The runner stamps its start time before the agent opens its session log, and the two clocks
     * are read a few milliseconds apart. Without slack a trace can look fractionally "too old" and
     * a perfectly good run would show as having produced none.
     */
    private const val TRACE_CLOCK_SLACK_MS = 2_000L

}
