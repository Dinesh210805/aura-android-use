package com.aura.aura_ui.mcp.log

/**
 * Render-time view model for the native agent trace screen (Trace-v2).
 *
 * A run is shown as a list of **turns** (the GenAI-span shape): each turn is one
 * LLM round-trip plus the tool invocations it triggered, in chronological order.
 * This is derived purely from the flat [SessionLog] lists — nothing is persisted —
 * so it stays correct for older logs and needs no schema migration.
 */
data class AgentTurn(
    /** 1-based turn number for display. */
    val number: Int,
    /** The LLM call that opened this turn. Null only for tools that preceded the first call. */
    val llm: LlmCall?,
    /** Tools the model invoked after [llm] and before the next LLM call. */
    val tools: List<ToolInvocation>,
) {
    /** Wall-clock cost of this turn (LLM + its tools), for the per-turn latency badge. */
    val totalMs: Long get() = (llm?.durationMs ?: 0L) + tools.sumOf { it.durationMs }
}

/** Run-level totals shown in the header. */
data class AgentTraceSummary(
    val toolCount: Int,
    val llmCount: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    /** Sum of prompt tokens served from a server-side cache — 0 if no call reported any. */
    val cachedTokens: Int,
    val wallClockMs: Long,
) {
    /** Share of prompt tokens that were cache hits, 0..1. Null when no call reported caching. */
    val cacheHitRatio: Float?
        get() = if (cachedTokens <= 0 || promptTokens <= 0) null else cachedTokens.toFloat() / promptTokens
}

/**
 * Group a session's LLM calls + tool invocations into chronological turns.
 *
 * Walks both lists merged by timestamp: each [LlmCall] starts a new turn; each
 * [ToolInvocation] attaches to the current turn. Tools that arrive before the first
 * LLM call form a leading turn with `llm = null` (rare, but never dropped).
 */
fun buildAgentTurns(session: SessionLog): List<AgentTurn> {
    val events = buildList {
        // Agent turns only: the research's side call runs beside turn 1 and, sorted in by time,
        // would open a "turn" of its own and steal turn 1's tools. It is shown with the research.
        session.llmCalls.filter { it.purpose == null }.forEach { add(it.timestampMillis to (it as Any)) }
        session.invocations.forEach { add(it.timestampMillis to (it as Any)) }
    }.sortedBy { it.first }.map { it.second }

    val turns = mutableListOf<AgentTurn>()
    var currentLlm: LlmCall? = null
    var currentTools = mutableListOf<ToolInvocation>()
    var opened = false

    fun flush() {
        if (opened || currentTools.isNotEmpty()) {
            turns += AgentTurn(number = turns.size + 1, llm = currentLlm, tools = currentTools)
        }
    }

    for (event in events) {
        when (event) {
            is LlmCall -> {
                flush()
                currentLlm = event
                currentTools = mutableListOf()
                opened = true
            }
            is ToolInvocation -> currentTools.add(event)
        }
    }
    flush()
    return turns
}

/**
 * One row of the trace: something said, or a model turn with the tools it fired.
 *
 * The screen was turns-only, which was correct while a session came from exactly one plane. Now
 * that a spoken request and the phone task it triggers share a session, a turns-only view would
 * render the taps and silently drop the sentence that asked for them — the same
 * write-it-but-never-read-it failure the conversation log itself started with.
 */
sealed interface TraceEntry {
    val atMillis: Long

    data class Spoken(val utterance: Utterance) : TraceEntry {
        override val atMillis: Long get() = utterance.atMillis
    }

    data class Turn(val turn: AgentTurn, override val atMillis: Long) : TraceEntry
}

/**
 * Merge speech and agent turns onto one clock.
 *
 * A turn is placed at its LLM call; a leading tool-only turn (tools before the first call) is
 * placed at its first tool, so it cannot collapse to timestamp 0 and jump to the top of a
 * conversation that started minutes earlier.
 */
fun buildTraceEntries(session: SessionLog): List<TraceEntry> {
    val turns = buildAgentTurns(session).map { turn ->
        val at = turn.llm?.timestampMillis
            ?: turn.tools.minOfOrNull { it.timestampMillis }
            ?: session.startedAtMillis
        TraceEntry.Turn(turn, at)
    }
    val spoken = session.utterances.map { TraceEntry.Spoken(it) }
    return (turns + spoken).sortedBy { it.atMillis }
}

/** Compute run-level totals for the header. */
fun summarize(session: SessionLog): AgentTraceSummary = AgentTraceSummary(
    toolCount = session.invocations.size,
    llmCount = session.llmCalls.size,
    promptTokens = session.llmCalls.sumOf { it.promptTokens ?: 0 },
    completionTokens = session.llmCalls.sumOf { it.completionTokens ?: 0 },
    totalTokens = session.llmCalls.sumOf { it.totalTokens ?: 0 },
    cachedTokens = session.llmCalls.sumOf { it.cachedTokens ?: 0 },
    wallClockMs = (session.endedAtMillis ?: session.startedAtMillis) - session.startedAtMillis,
)
