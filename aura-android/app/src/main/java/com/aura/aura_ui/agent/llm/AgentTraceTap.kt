package com.aura.aura_ui.agent.llm

import android.util.Log

/**
 * Process-wide tap for platform-stack activity during an on-device agent run.
 *
 * Complements [AgentLlmTap] and [AgentPerceptionTap] by capturing events from
 * the orchestration layer: run assembly (tools, remote servers, skills, memory),
 * hook decisions (Deny/Confirm/Rewrite), tool origin (local vs remote MCP),
 * skill loads, and memory write-backs.
 *
 * [AgentRunLogger] installs a [sink] for the duration of a run; various callsites
 * emit events keyed to the current tool call or run phase. Only one on-device run
 * is active at a time, so a single nullable sink is sufficient.
 *
 * All events are fail-open: failures are logged and swallowed, never breaking
 * the run or the tool/LLM path.
 */
object AgentTraceTap {

    /**
     * Run manifest: the collection of tools, remote servers, skills, and injected hints
     * that were assembled for a run. Emitted once at run start.
     */
    data class RunAssemblyEvent(
        val localToolCount: Int,
        val remoteServers: List<RemoteServerInfo>,
        val skills: List<String>,
        val injectedHints: String?,
    )

    /**
     * A remote MCP server attached to the run.
     */
    data class RemoteServerInfo(
        val name: String,
        val toolCount: Int,
        val instructions: String?,
    )

    /**
     * The origin of a tool: local (on-device) or remote (MCP server).
     */
    data class ToolOriginEvent(
        val toolName: String,
        val origin: String, // "local" | "mcp:<server>"
    )

    /**
     * A skill was loaded into the agent's context.
     */
    data class SkillLoadedEvent(
        val skillName: String,
        val content: String?, // the full skill text, or null if not captured
    )

    /**
     * A verified path was recorded to the learnings store after a successful run.
     */
    data class MemoryWriteEvent(
        val goalKey: String,
        val path: List<String>,
    )

    /**
     * The run ledger's plan checklist changed (set_plan / mark_step). Latest snapshot wins —
     * the Logs screen renders live "done/total" progress from it.
     */
    data class PlanUpdateEvent(
        val done: Int,
        val total: Int,
        val steps: List<String>, // "status: text" per step
    )

    /**
     * The run's meters and the limits they were measured against, emitted once as the run ends.
     *
     * Exists because none of this is observable any other way: the context window is persisted in
     * `EncryptedSharedPreferences` (unreadable from adb), and the budget's own counters live only
     * in memory for the life of the run. Without this event, "compaction is capped now" and "the
     * request meter counts every call" are claims nobody — user, developer, or future reader — can
     * check on a real device.
     *
     * [tokensReported] is what makes [tokensSpent] interpretable: it distinguishes "the provider
     * told us 12k" from "we guessed 12k because it reported nothing".
     */
    data class BudgetEvent(
        val requests: Int,
        /** The user's opt-in per-run request cap, or null when they set none (the default). */
        val maxRequests: Int?,
        /**
         * Tokens this run caused the provider to process for the first time. Purely an instrument
         * since 2026-09-22 — there is no token ceiling to compare it against, which is why no cap
         * is reported beside it. See `RunBudget` for why a cumulative sum was never a valid one.
         */
        val tokensSpent: Int,
        /** True when the figure came from the provider's `usage`; false when it is our estimate. */
        val tokensReported: Boolean,
        /**
         * The pre-flight estimate, recorded alongside the reported figure so the gap is visible.
         * Measured on device the estimate under-counts several-fold, structurally: it sums the
         * prompt's messages, while the 50-odd tool schemas ride in the request's `tools` field.
         */
        val tokensEstimated: Int,
        /**
         * Prompt tokens the provider served from its own cache, which [tokensSpent] deliberately
         * does not include. Recorded because the discount is a policy choice: without this figure
         * a reader cannot tell a cheap run from a well-cached expensive one.
         */
        val tokensCached: Int = 0,
        val elapsedMs: Long,
        val maxWallClockMs: Long,
        /** The threshold compaction actually ran against this run. */
        val compactThresholdTokens: Int,
        /** The model's published input limit, or null when the provider published none. */
        val contextWindow: Int?,
        val retryMaxAttempts: Int,
        /** Worst-case milliseconds a single call could have spent asleep across its retries. */
        val retryWorstCaseSleepMs: Long,
        /** Which ceiling ended the run — `TOKENS` / `REQUESTS` / `WALL_CLOCK`, or null if none did. */
        val endedByLimit: String?,
    )

    sealed class Event {
        data class Assembly(val event: RunAssemblyEvent) : Event()
        data class Budget(val event: BudgetEvent) : Event()
        data class ToolOrigin(val event: ToolOriginEvent) : Event()
        data class SkillLoaded(val event: SkillLoadedEvent) : Event()
        data class MemoryWrite(val event: MemoryWriteEvent) : Event()
        data class PlanUpdate(val event: PlanUpdateEvent) : Event()
        data class Research(val trace: com.aura.aura_ui.agent.research.TaskResearch.ResearchTrace) : Event()

        /** A step in a tool call's journey; see [reportStep]. */
        data class Step(val toolName: String, val stage: String, val name: String, val verdict: String, val detail: String?, val ms: Long?) : Event()

        /** A line the overlay just showed. */
        data class Narration(val kind: String, val text: String, val headline: String?, val aboutCall: Boolean = true) : Event()
    }

    @Volatile
    var sink: ((Event) -> Unit)? = null

    /** True when a logger wants events captured — lets callers skip expensive work otherwise. */
    val isActive: Boolean get() = sink != null

    fun reportAssembly(
        localToolCount: Int,
        remoteServers: List<RemoteServerInfo>,
        skills: List<String>,
        injectedHints: String?,
    ) {
        val s = sink ?: return
        runCatching {
            s(Event.Assembly(RunAssemblyEvent(localToolCount, remoteServers, skills, injectedHints)))
        }.onFailure { Log.w(TAG, "Failed to report run assembly event", it) }
    }

    fun reportBudget(event: BudgetEvent) {
        val s = sink ?: return
        runCatching { s(Event.Budget(event)) }
            .onFailure { Log.w(TAG, "Failed to report budget event", it) }
    }

    fun reportToolOrigin(toolName: String, origin: String) {
        val s = sink ?: return
        runCatching {
            s(Event.ToolOrigin(ToolOriginEvent(toolName, origin)))
        }.onFailure { Log.w(TAG, "Failed to report tool origin event", it) }
    }

    fun reportSkillLoaded(skillName: String, content: String? = null) {
        val s = sink ?: return
        runCatching {
            s(Event.SkillLoaded(SkillLoadedEvent(skillName, content)))
        }.onFailure { Log.w(TAG, "Failed to report skill loaded event", it) }
    }

    fun reportMemoryWrite(goalKey: String, path: List<String>) {
        val s = sink ?: return
        runCatching {
            s(Event.MemoryWrite(MemoryWriteEvent(goalKey, path)))
        }.onFailure { Log.w(TAG, "Failed to report memory write event", it) }
    }

    fun reportPlanUpdate(done: Int, total: Int, steps: List<String>) {
        val s = sink ?: return
        runCatching {
            s(Event.PlanUpdate(PlanUpdateEvent(done, total, steps)))
        }.onFailure { Log.w(TAG, "Failed to report plan update event", it) }
    }

    fun reportResearch(trace: com.aura.aura_ui.agent.research.TaskResearch.ResearchTrace) {
        val s = sink ?: return
        runCatching { s(Event.Research(trace)) }.onFailure { Log.w(TAG, "Failed to report research", it) }
    }

    /**
     * One step of [toolName]'s journey — a pre/post hook, a server gate, the dispatch, a settle.
     * Filed onto the open call with that name; a step for any other call (the research's own
     * web_search, running beside the agent) is kept on the research record instead.
     */
    fun reportStep(toolName: String, stage: String, name: String, verdict: String, detail: String? = null, ms: Long? = null) {
        val s = sink ?: return
        runCatching { s(Event.Step(toolName, stage, name, verdict, detail, ms)) }
            .onFailure { Log.w(TAG, "Failed to report pipeline step", it) }
    }

    fun reportNarration(kind: String, text: String, headline: String?, aboutCall: Boolean = true) {
        val s = sink ?: return
        runCatching { s(Event.Narration(kind, text, headline, aboutCall)) }.onFailure { Log.w(TAG, "Failed to report narration", it) }
    }

    private const val TAG = "AgentTraceTap"
}
