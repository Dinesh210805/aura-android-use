package com.aura.aura_ui.agent

/**
 * A live step in the on-device agent's run, emitted as the Koog loop executes so the
 * UI can show the *real* agent process (tool calls, outputs) instead of a static
 * "Thinking…". Emitted from [AuraAgent] via Koog's EventHandler; consumed by the
 * overlay ([com.aura.aura_ui.overlay.AuraOverlayService]).
 *
 * Deliberately **structured** (not pre-formatted display strings): the same stream
 * feeds two surfaces with different needs —
 *  - the chat trace (full history, human-readable), and
 *  - the automation behaviour (minimize-to-pill + the pill's current verb), which
 *    needs the raw [tool] name + the start/complete distinction to classify a run as
 *    an automation (a WRITE-scoped tool — see `McpToolScopes`) and derive the verb.
 * Pre-formatting here would force the automation slice to re-parse strings.
 */
sealed interface AgentStreamEvent {

    /** The model decided to call [tool] with [args] (raw JSON args). Fires BEFORE execution. */
    data class ToolStarting(val tool: String, val args: String) : AgentStreamEvent

    /** [tool] finished; [summary] is a short text rendering of its result. */
    data class ToolCompleted(val tool: String, val summary: String) : AgentStreamEvent

    /** [tool] threw; [error] is the failure message. */
    data class ToolFailed(val tool: String, val error: String) : AgentStreamEvent

    /** The agent produced its final natural-language [result]; the run is over. */
    data class Finished(val result: String) : AgentStreamEvent
}
