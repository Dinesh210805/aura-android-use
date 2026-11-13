package com.aura.mcp.tools

import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `ask_user` for outside MCP clients: put one question to the person holding the phone and wait
 * for the answer.
 *
 * - Contract: [ask] shows the question on the phone (and says it aloud in a voice conversation)
 *   and returns what the person answered, or a sentence saying they did not; it never throws for
 *   an unanswered question, so a slow human is not a tool failure.
 * - Why only for outside clients: AURA's own agents register their own `ask_user`, and two tools
 *   with one name would collide in their tool lists. `McpServerBuilder.build` registers this only
 *   when it is given an asker.
 * - Scope: READ. It changes nothing on the phone.
 */
internal fun Server.registerAskUserTool(ask: suspend (question: String, options: List<String>) -> String) {
    scopedTool(
        name = "ask_user",
        description = "Ask the person holding the phone one question and wait up to 2 minutes for the answer. " +
            "Only for what they alone know, or a go-ahead before sending, paying or posting.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "question" to stringSchema("One short question."),
                    "options" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("array"),
                            "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                            "description" to JsonPrimitive("Up to $MAX_OPTIONS short choices."),
                        ),
                    ),
                ),
            ),
            required = listOf("question"),
        ),
    ) { request ->
        val question = (request.arguments?.get("question") as? JsonPrimitive)?.contentOrNull?.trim()
        if (question.isNullOrEmpty()) {
            return@scopedTool CallToolResult(
                content = listOf(TextContent("'question' is required: one short question for the person.")),
                isError = true,
            )
        }
        val options = (request.arguments?.get("options") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_OPTION_CHARS) }
            .take(MAX_OPTIONS)
        CallToolResult(content = listOf(TextContent(ask(question.take(MAX_QUESTION_CHARS), options))), isError = false)
    }
}

private const val MAX_OPTIONS = 4
private const val MAX_OPTION_CHARS = 80
private const val MAX_QUESTION_CHARS = 300
