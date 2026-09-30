package com.aura.aura_ui.agent.strategy

import com.aura.aura_ui.agent.llm.ReplySanitizer

/**
 * Guards the tool loop against a *thinking-only* turn: an assistant message that carries only
 * reasoning (`<think>`/`<thought>` blocks, or nothing) with no tool call and no real answer.
 *
 * Small models — observed with Gemini 3.x Flash-Lite on-device — sometimes "think out loud" about
 * the next step and stop without emitting the tool call. The stock Koog loop routes any text turn
 * to `finish`, so such a turn silently abandons the task mid-flight (the strip then yields a blank
 * final answer → the "no final answer" fallback). Instead the loop nudges the model to act, up to
 * [MAX_NUDGES] times per run, before giving up.
 */
internal object EmptyTurnPolicy {
    /** Max "you only reasoned — now act" nudges before the loop lets the run finish. */
    const val MAX_NUDGES = 2

    /** True when [assistantText] is pure reasoning: nothing survives once thinking is stripped. */
    fun isThinkingOnly(assistantText: String): Boolean =
        ReplySanitizer.stripThinking(assistantText).isBlank()
}

/**
 * Per-run budget for thinking-only nudges. Built once per run (the strategy is created per
 * `runSpike`), so a single stubborn model can be nudged at most [EmptyTurnPolicy.MAX_NUDGES] times
 * total before the loop lets the run finish. Not thread-safe by design — the tool loop is
 * sequential.
 */
internal class NudgeBudget(private val max: Int = EmptyTurnPolicy.MAX_NUDGES) {
    var used: Int = 0
        private set

    /** Consumes one nudge if any remain; returns whether a nudge was granted. */
    fun tryConsume(): Boolean = if (used < max) { used++; true } else false
}

/** The message re-injected when the model reasoned without acting — pushes it to emit a tool call. */
internal const val EMPTY_TURN_NUDGE =
    "Your last turn had only reasoning, so nothing happened. This turn, call the next tool — " +
        "read_screen if you need to look — or, if the task is finished, call end_session."
