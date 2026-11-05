package com.aura.aura_ui.services

import com.aura.aura_ui.agent.mcpbridge.hooks.StatusNarrationHook
import com.aura.aura_ui.services.AgentStatusRegistry.Beat
import com.aura.mcp.bridge.SessionLogSink
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A remote MCP client's run as the overlay shows it: the same status strip and run controls an
 * on-device run gets, then a report card when it ends.
 *
 * - Contract: a run starts at the first tool call from an outside client and ends on an accepted
 *   `end_session`, the user's Cancel ([stopByUser]), or [IDLE_END_MS] without a call. It never
 *   idles out while the human holds the lock, so Resume/Cancel stay on screen until they choose.
 * - Contract: thread-safe; the sink calls arrive on MCP request threads.
 * - Reads/Writes: publishes beats to [AgentStatusRegistry]; nothing on disk.
 * - Change together: `AuraOverlayService` (renders [active] and [report]),
 *   `AssistantForegroundService` (wraps the remote server's session sink in [Sink] and ORs
 *   [isRefusing] into its `pausedByHuman`).
 */
class RemoteRun internal constructor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val isPaused: () -> Boolean = { false },
    private val scope: CoroutineScope? = null,
    private val publish: (Beat) -> Unit = AgentStatusRegistry::setBeat,
) {
    enum class Outcome { DONE, PARTIAL, FAILED, STOPPED, PAUSED, WENT_QUIET }

    /** What the report card draws. [markdown] is the client's own report, untrusted. */
    data class Report(
        val outcome: Outcome,
        val client: String,
        val durationMs: Long,
        val steps: Int,
        val summary: String?,
        val markdown: String?,
        val recent: List<String>,
    )

    private data class Live(
        val client: String,
        val startedAtMs: Long,
        val steps: Int = 0,
        val recent: List<String> = emptyList(),
        val pendingArgs: String? = null,
    )

    private val narrator = StatusNarrationHook()
    private val json = Json { ignoreUnknownKeys = true }

    private val _active = MutableStateFlow(false)

    /** True while an outside client is driving; the overlay shows run controls for it. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _report = MutableStateFlow<Report?>(null)

    /** The card to show, or null. Cleared by the next tool call, Resume, or the close button. */
    val report: StateFlow<Report?> = _report.asStateFlow()

    private var live: Live? = null
    private var lastCallMs = 0L
    private var stoppedAtMs: Long? = null

    /** Returns true when this call started a new run. */
    @Synchronized
    fun onToolStart(toolName: String, argsJson: String?, client: String?): Boolean {
        lastCallMs = clock()
        // A card left up would sit over the screen the client is about to tap.
        _report.value = null
        val started = live == null
        val run = live ?: Live(client?.takeIf { it.isNotBlank() } ?: "MCP client", clock()).also {
            _active.value = true
            startIdleWatch()
        }
        live = run.copy(pendingArgs = argsJson)
        return started
    }

    @Synchronized
    fun onToolEnd(toolName: String, success: Boolean, outputSummary: String) {
        val run = live ?: return
        lastCallMs = clock()
        if (toolName == END_SESSION) return
        val result = CallToolResult(content = listOf(TextContent(outputSummary)), isError = !success)
        val beat = runCatching {
            if (success) narrator.narrate(toolName, args(run.pendingArgs), result) else narrator.narrateFailure(result)
        }.getOrElse { Beat(AgentStatusRegistry.Kind.WORK, "Working…") }
        val steps = run.steps + 1
        publish(beat.copy(headline = "${run.client} · step $steps"))
        live = run.copy(
            steps = steps,
            recent = (run.recent + beat.text).distinct().takeLast(MAX_RECENT),
            pendingArgs = null,
        )
    }

    /** A gate refused a call. Still a sign the client is there, so it keeps a stop in force. */
    @Synchronized
    fun onToolRefused() {
        lastCallMs = clock()
    }

    @Synchronized
    fun onEndSession(reason: String, outcome: String?, report: String?) {
        val result = when (outcome?.lowercase()) {
            "partial" -> Outcome.PARTIAL
            "failure" -> Outcome.FAILED
            else -> Outcome.DONE
        }
        finish(result, reason.takeUnless { it.isBlank() || it == "agent-end" }, report?.takeIf { it.isNotBlank() })
    }

    /** The user pressed Cancel: end the run and refuse this client until it goes quiet. */
    @Synchronized
    fun stopByUser() {
        if (live == null) return
        stoppedAtMs = clock()
        finish(Outcome.STOPPED, null, null)
    }

    /** The user pressed Pause mid-run: show progress so far. Not for pauses inferred from a touch. */
    @Synchronized
    fun notePaused() {
        val run = live ?: return
        _report.value = reportOf(run, Outcome.PAUSED, null, null)
    }

    @Synchronized
    fun noteResumed() {
        if (_report.value?.outcome == Outcome.PAUSED) _report.value = null
    }

    fun dismissReport() {
        _report.value = null
    }

    /** Ends a run whose client stopped calling. Called by the idle watch; public for tests. */
    @Synchronized
    fun checkIdle() {
        if (live == null || isPaused()) return
        if (clock() - lastCallMs >= IDLE_END_MS) finish(Outcome.WENT_QUIET, null, null)
    }

    /**
     * True while a client the user cancelled is still calling. The server treats it like a pause.
     *
     * - Why bounded twice: [STOP_QUIET_MS] lets a client that obeyed the refusal start fresh
     *   later; [STOP_CAP_MS] keeps one that never stops retrying from locking the phone forever.
     */
    val isRefusing: Boolean
        @Synchronized get() {
            val stopped = stoppedAtMs ?: return false
            val now = clock()
            val refusing = now - stopped < STOP_CAP_MS && now - lastCallMs < STOP_QUIET_MS
            if (!refusing) stoppedAtMs = null
            return refusing
        }

    private fun finish(outcome: Outcome, summary: String?, markdown: String?) {
        val run = live ?: return
        live = null
        _active.value = false
        _report.value = reportOf(run, outcome, summary, markdown)
    }

    private fun reportOf(run: Live, outcome: Outcome, summary: String?, markdown: String?) = Report(
        outcome = outcome,
        client = run.client,
        durationMs = clock() - run.startedAtMs,
        steps = run.steps,
        summary = summary,
        markdown = markdown,
        recent = run.recent,
    )

    private fun args(argsJson: String?): JsonObject =
        argsJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    private var idleWatch: Job? = null

    /** One loop at a time: back-to-back runs reuse the live one instead of stacking another. */
    private fun startIdleWatch() {
        if (idleWatch?.isActive == true) return
        idleWatch = scope?.launch {
            while (_active.value) {
                delay(IDLE_TICK_MS)
                checkIdle()
            }
        }
    }

    /** Wraps the remote server's session sink so every call also drives [run]. */
    class Sink(
        private val delegate: SessionLogSink,
        private val run: RemoteRun = shared,
        private val onRunStarted: () -> Unit,
    ) : SessionLogSink by delegate {
        override fun onToolStart(toolName: String, argsJson: String?, tokenId: String?, agentLabel: String?) {
            delegate.onToolStart(toolName, argsJson, tokenId, agentLabel)
            if (run.onToolStart(toolName, argsJson, agentLabel)) onRunStarted()
        }

        override fun onToolEnd(toolName: String, success: Boolean, outputSummary: String, durationMs: Long) {
            delegate.onToolEnd(toolName, success, outputSummary, durationMs)
            run.onToolEnd(toolName, success, outputSummary)
        }

        override fun onToolRefused(toolName: String, reason: String, tokenId: String?, agentLabel: String?) {
            delegate.onToolRefused(toolName, reason, tokenId, agentLabel)
            run.onToolRefused()
        }

        override fun endSession(reason: String, outcome: String?, report: String?) {
            delegate.endSession(reason, outcome, report)
            run.onEndSession(reason, outcome, report)
        }
    }

    companion object {
        private const val END_SESSION = "end_session"
        private const val MAX_RECENT = 6

        /** Matches `ControlLockStore.REMOTE_DRIVING_WINDOW_MS`: a minute without calls is gone. */
        const val IDLE_END_MS = 60_000L
        private const val IDLE_TICK_MS = 5_000L
        const val STOP_QUIET_MS = 60_000L
        const val STOP_CAP_MS = 5 * 60_000L

        val shared: RemoteRun by lazy {
            RemoteRun(
                isPaused = { ControlLockStore.shared.isPausedByHuman },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
    }
}
