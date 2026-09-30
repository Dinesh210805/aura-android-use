package com.aura.aura_ui.agent.hitl

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The load-bearing logic behind the `ask_user` Koog tool (glue in
 * AskUserKoogTools is thin, compile-verified adaptation — this class is the
 * unit-tested part, mirroring [com.aura.aura_ui.agent.ledger.LedgerPlanTools]).
 *
 * Result-shaping rules that matter:
 *  - **Timeout/dismissal are NOT tool errors.** "The user walked away" is a
 *    valid state; an isError result would feed ActionGuard's failure-loop
 *    counter and could abort a run because a human was slow.
 *  - The answer text is returned verbatim to the model but must never enter
 *    learnings — `ask_user` is classified read-only in PathStepSanitizer.
 */
class AskUserTools(
    private val broker: AskUserBroker,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    suspend fun askUser(args: JsonObject): CallToolResult {
        val question = args["question"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return err("'question' is required — one short, specific question for the user.")

        val options = (args["options"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?.map { it.take(MAX_OPTION_CHARS) }
            ?.take(MAX_OPTIONS)
            .orEmpty()

        return when (val a = broker.ask(question.take(MAX_QUESTION_CHARS), options, timeoutMs)) {
            is AskUserAnswer.Answered ->
                ok("User answered: \"${a.text}\". Continue the task using this choice.")
            AskUserAnswer.Dismissed ->
                ok(
                    "The user dismissed the question without answering. Proceed with " +
                        "your best judgment, or end the task and say what you needed.",
                )
            AskUserAnswer.Timeout ->
                ok(
                    "The user did not answer within ${timeoutMs / 1000} seconds. Proceed " +
                        "with your best judgment, or end the task and say what you needed.",
                )
            AskUserAnswer.Busy ->
                err("A question is already waiting for the user — never stack questions.")
        }
    }

    private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = false)
    private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)

    companion object {
        const val MAX_OPTIONS = 4
        const val MAX_OPTION_CHARS = 80
        const val MAX_QUESTION_CHARS = 300
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
