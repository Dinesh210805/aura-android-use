package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `do_steps`: runs a known sequence of phone actions, each found by its label on a fresh screen,
 * in one model call. Its steps usually come from a skill's `steps:` block.
 *
 * - Contract: each targeted step reads the screen (unless nothing acted since the last read) and
 *   resolves its [Step.target] to exactly one element on it ([find]); none or several stops the
 *   chain. Why read: a tap's settle does not refresh the server's element cache, so a lookup
 *   without a read could match the previous screen. Every action goes through [call], the
 *   hub's own gated path, so approvals, deny lists, taint and commit cards apply exactly as to a
 *   call the model makes. The chain stops at the first refused or failed step, or when a step's
 *   [Step.expect] is not on the screen after it, and returns what ran plus the current screen.
 * - Why not the server's `run_steps`: that runs inside the phone server, past [SafetyGate].
 * - Fails: closed. An action outside [CHAIN_ACTIONS] or an unfilled `<text>` stops the chain
 *   before it acts.
 */
class StepRunner(
    /** Runs one tool through the hub's gate; null when the tool is not available. */
    private val call: suspend (TurnState, String, JsonObject) -> ToolOutcome?,
    /** The som_ids carrying a label on the latest screen read. */
    private val find: (String) -> List<Int>,
) {
    class Result(val text: String, val ok: Boolean)

    suspend fun run(turn: TurnState, steps: List<Step>): Result {
        val done = mutableListOf<String>()
        var screen = ""
        var looked = false

        suspend fun look(): String? = call(turn, READ_SCREEN, JsonObject(emptyMap()))?.takeIf { !it.isError }?.text
            ?.also { screen = it; looked = true }

        fun stop(i: Int, why: String): Result = Result(report(done, steps.size, "Stopped at step ${i + 1}: $why", screen), ok = false)

        for ((i, s) in steps.withIndex()) {
            if (s.action !in CHAIN_ACTIONS) return stop(i, "${s.action} cannot run in do_steps; call it on its own.")
            if (s.text == Step.TEXT_SLOT) return stop(i, "fill in the text to type instead of ${Step.TEXT_SLOT}.")
            val args = LinkedHashMap<String, JsonPrimitive>()
            s.args.forEach { (k, v) -> args[k] = JsonPrimitive(v) }
            s.text?.let { args[if (s.action == "set_value") "value" else "text"] = JsonPrimitive(it) }
            s.target?.let { target ->
                if (!looked) look() ?: return stop(i, "could not read the screen.")
                val ids = find(target)
                when {
                    ids.isEmpty() -> return stop(i, "nothing labelled \"$target\" on the screen.")
                    ids.size > 1 -> return stop(i, "\"$target\" matches ${ids.size} elements (${ids.joinToString()}); act on the right one yourself.")
                }
                args["som_id"] = JsonPrimitive(ids.single())
            }
            val out = call(turn, s.action, JsonObject(args)) ?: return stop(i, "${s.action} is not available.")
            screen = out.text
            looked = false
            if (out.isError) return stop(i, out.text.take(MAX_REASON))
            s.expect?.let { want ->
                if (!shows(out.text, want) && (look() == null || !shows(screen, want))) {
                    return stop(i, "\"$want\" did not appear after ${s.action}.")
                }
            }
            done += "${i + 1}. ${s.action}${s.target?.let { " \"$it\"" }.orEmpty()} ok"
        }
        return Result(report(done, steps.size, null, screen), ok = true)
    }

    private fun shows(text: String, want: String) = want.trim().lowercase() in text.lowercase()

    private fun report(done: List<String>, of: Int, stopped: String?, screen: String): String = buildString {
        append("Ran ${done.size} of $of steps.\n")
        done.forEach { append(it).append('\n') }
        stopped?.let { append(it).append(" Later steps did not run; continue from the screen below.\n") }
        if (screen.isNotBlank()) append("Screen now:\n").append(screen)
    }.trim()

    companion object {
        const val NAME = "do_steps"
        private const val READ_SCREEN = "read_screen"
        private const val MAX_REASON = 400
        const val MAX_STEPS = 20

        /** Actions on the screen or the app in front. No looks, no links, nothing that leaves the phone. */
        val CHAIN_ACTIONS = setOf("launch_app", "tap", "type_text", "set_value", "press_enter", "press_key", "scroll", "swipe", "wait_for")

        /**
         * A step from the model's JSON: `action`, `target`, `text`, `expect`, and the action's own
         * arguments either nested under `args` (as a skill's `steps:` line holds them, which also
         * keeps tap's own `action` apart) or as other plain top-level fields. A `som_id` is dropped:
         * targets are found by label.
         */
        fun parse(o: JsonObject): Step? {
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val action = s("action") ?: return null
            fun plain(obj: JsonObject, skip: Set<String>) =
                obj.filterKeys { it !in skip }.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            val top = plain(o, setOf("action", "target", "text", "expect", "som_id", "args"))
            val nested = (o["args"] as? JsonObject)?.let { plain(it, setOf("som_id", "text", "value")) }.orEmpty()
            return Step(action, s("target"), s("text"), s("expect"), top + nested)
        }
    }
}

/** The `do_steps` tool. [onChain] hears which skill's steps ran and whether they all did, for the curator. */
fun doStepsTool(runner: StepRunner, onChain: suspend (skill: String?, ok: Boolean) -> Unit): NativeTool {
    val step = ToolParameterType.Object(
        properties = listOf(
            ToolParameterDescriptor("action", "A phone action: ${StepRunner.CHAIN_ACTIONS.joinToString()}.", ToolParameterType.String),
            ToolParameterDescriptor("target", "The element's label exactly as the screen shows it. Leave out for an action on no element.", ToolParameterType.String),
            ToolParameterDescriptor("text", "What to type, for type_text and set_value.", ToolParameterType.String),
            ToolParameterDescriptor("expect", "Words that must be on the screen after this step, or the chain stops.", ToolParameterType.String),
            ToolParameterDescriptor("app", "For launch_app.", ToolParameterType.String),
            ToolParameterDescriptor("direction", "For scroll and swipe.", ToolParameterType.String),
            ToolParameterDescriptor("key", "For press_key.", ToolParameterType.String),
        ),
        requiredProperties = listOf("action"),
    )
    return NativeTool(
        ToolSpec(
            ToolDescriptor(
                name = StepRunner.NAME,
                description = DO_STEPS_DESCRIPTION,
                requiredParameters = listOf(ToolParameterDescriptor("steps", "Up to ${StepRunner.MAX_STEPS} steps, in order.", ToolParameterType.List(step))),
                optionalParameters = listOf(ToolParameterDescriptor("skill", "The skill these steps come from, if any.", ToolParameterType.String)),
            ),
            // Each step is gated on its own; the chain itself only reads and dispatches.
            ToolRisk.READ,
            ToolOrigin.NATIVE,
            ToolHub.SCREEN,
            resultOrigin = null,
        ),
    ) { args, turn ->
        val steps = (args["steps"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(StepRunner::parse) }.orEmpty()
        if (steps.isEmpty() || steps.size > StepRunner.MAX_STEPS) {
            return@NativeTool SourceResult("Give 1 to ${StepRunner.MAX_STEPS} steps, each with an action.", isError = true)
        }
        val result = runner.run(turn, steps)
        onChain((args["skill"] as? JsonPrimitive)?.contentOrNull, result.ok)
        SourceResult(result.text, isError = !result.ok)
    }
}

private const val DO_STEPS_DESCRIPTION =
    "Run up to 20 phone actions in one call. Use it only when you already know the steps: from a skill's " +
        "`steps:` line or from the screen you just read. Never use it to guess. Each `target` is the " +
        "element's label exactly as the screen shows it. Replace every `<text>` with the real text to type. " +
        "It stops at the first step that fails, or whose label or `expect` text is missing, and returns the " +
        "screen; continue by hand from there. Send, pay and order steps still show the user an approval card. " +
        "Pass `skill` when the steps came from a skill."
