package com.aura.aura_ui.agent.strategy

import com.aura.aura_ui.agent.llm.ToolCallDebris

/**
 * Why a turn must be re-requested rather than allowed to end the run.
 *
 * The stock Koog loop routes **any** text turn to `finish`. That is right for a real answer and
 * wrong for the two shapes below, both of which are a turn where the model meant to act and
 * nothing happened — so finishing on one abandons the task mid-flight and speaks whatever the
 * model happened to be holding.
 *
 * ### [THINKING_ONLY]
 *
 * Gemini 3.x Flash-Lite reasons out loud and forgets to emit the call. Guarded since the
 * battery-settings stall; see [EmptyTurnPolicy].
 *
 * ### [TEXT_SHAPED_TOOL_CALL]
 *
 * The model writes the call as prose — `<call>default_api:tap{som_id:110}</call>`. Four runs in
 * the 2026-08-26 eval suite did this; task 4's came back with
 * `finishReason: function_call_filter: MALFORMED_FUNCTION_CALL` and the run **simply ended**, no
 * repair attempted, the markup becoming the run's outcome.
 *
 * [ToolCallDebris] already guarantees no such markup is ever spoken, but it runs at the TTS seam
 * — by then the run is over and the work is lost. This is the same finding one step earlier,
 * where it is still repairable: re-ask, and the turn usually comes back as a real call.
 *
 * Deliberately keyed off the **debris shape**, not off Gemini's `MALFORMED_FUNCTION_CALL` finish
 * reason. That string is Gemini-specific, arrives through a Koog type that does not surface it to
 * the strategy, and — as tasks 7/9/10 show — three of the four runs produced identical debris
 * with an ordinary `stop` finish reason. The symptom is the reliable signal; the finish reason is
 * not.
 */
internal enum class TurnDefect {
    THINKING_ONLY,
    TEXT_SHAPED_TOOL_CALL,
    ;

    /**
     * What to tell the model. Per-defect on purpose: sending the thinking-only nudge ("do not
     * just think") to a model that *did* try to act, and merely serialised it wrong, describes a
     * failure that did not happen and leaves the real one unnamed.
     */
    val nudge: String
        get() = when (this) {
            THINKING_ONLY -> EMPTY_TURN_NUDGE
            TEXT_SHAPED_TOOL_CALL -> TEXT_SHAPED_TOOL_CALL_NUDGE
        }

    companion object {
        /**
         * Classify a completed assistant turn, or null when it is fine to finish on.
         *
         * [hasToolCall] short-circuits everything: a turn that really called a tool is the loop
         * working, and re-requesting would discard the call the model just made — including the
         * common shape where a legitimate call rides alongside chatty prose.
         *
         * ### Why debris alone is not enough
         *
         * The first cut here was `hasDebris(text)`, and it was wrong in both directions.
         *
         * **False positives cost more here than at the TTS seam.** [ToolCallDebris.hasDebris]
         * gates two very different actions: at the seam a false positive *strips* text, but here
         * it appends "make exactly one real function call" — so a final answer that merely
         * *quotes* markup (a browser or `read_screen` result carrying `<script`, a page whose
         * text contains `outcome="`) would be pushed into a spurious tool call **after** the
         * mutating work was already done.
         *
         * **And three of the four observed runs did not need repairing.** Of the 2026-08-26
         * quartet only task 4 actually died; tasks 7, 9 and 10 scored PASS, because the sentence
         * the user needed was sitting inside the debris and [ToolCallDebris.clean] recovers it.
         * Re-requesting those would spend a turn to re-derive an answer already in hand.
         *
         * So the test is: debris **and nothing salvageable left after cleaning it**. That is
         * exactly task 4 — pure machinery, no answer, the run that ended on
         * `MALFORMED_FUNCTION_CALL` — and exactly the case where finishing costs the whole task
         * and re-asking costs one turn. A quoted-markup answer keeps its prose through `clean`,
         * so it never reaches the nudge.
         *
         * Debris is checked before thinking-only because debris is not blank, so the
         * thinking-only test would miss it and let the run finish on markup.
         */
        fun of(hasToolCall: Boolean, assistantText: String): TurnDefect? = when {
            hasToolCall -> null
            ToolCallDebris.hasDebris(assistantText) &&
                ToolCallDebris.clean(assistantText).isBlank() -> TEXT_SHAPED_TOOL_CALL
            EmptyTurnPolicy.isThinkingOnly(assistantText) -> THINKING_ONLY
            else -> null
        }
    }
}

/**
 * One [NudgeBudget] per defect, built once per run.
 *
 * Separate rather than shared so that an early thinking-only turn cannot spend the allowance a
 * later malformed call needs — and, more importantly, so adding the second defect does not
 * silently change how many nudges the existing thinking-only path gets. Not thread-safe by
 * design: the tool loop is sequential.
 */
internal class NudgeBudgets(
    private val perDefect: Map<TurnDefect, NudgeBudget> =
        TurnDefect.entries.associateWith { NudgeBudget() },
) {
    /** Consumes one nudge for [defect] if any remain; returns whether one was granted. */
    fun tryConsume(defect: TurnDefect): Boolean = perDefect.getValue(defect).tryConsume()

    /** How many nudges [defect] has spent this run — for logging. */
    fun used(defect: TurnDefect): Int = perDefect.getValue(defect).used
}

/**
 * Re-injected when the model wrote its tool call as text.
 *
 * Names the actual failure ("no tool ran") rather than scolding, lists the exact wrappers observed
 * on device so the model can recognise its own output, and gives it two legal exits — a real call,
 * or a plain answer — because a model with nothing left to do must not be trapped into inventing
 * a call. Carries no `<tag>` or `key: "value"` shapes itself: a nudge that tripped
 * [ToolCallDebris.hasDebris] would re-defect the very next turn it is quoted into.
 */
internal const val TEXT_SHAPED_TOOL_CALL_NUDGE =
    "Your last turn wrote a tool call as text, so nothing ran. Make it a real function call: " +
        "one call, valid JSON arguments, no tags or prose around it. If the task is finished, " +
        "call end_session. If you cannot call a tool, reply with your answer in plain sentences."
