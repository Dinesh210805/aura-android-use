package com.aura.aura_ui.agent.mcpbridge.hooks

import com.aura.aura_ui.utils.AgentLogger
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Records what AURA looked up and what came back, so a run's research can be read afterwards.
 *
 * ### Why this needs its own hook
 *
 * "Look it up when you are unsure" (`Doctrine.research_unfamiliar`) is an instruction, not an
 * enforced rule — nothing checks that a lookup happened, and nothing records what it found.
 * That leaves the two questions you actually want answered after a run invisible: **did it
 * search at all**, and **was the answer it acted on any good**. A wrong step taken confidently
 * looks identical in every other log to a wrong step taken blindly.
 *
 * Both routes are covered, because which one exists depends on the device: `web_search` when
 * a Tavily key is present, the browser pair when it is not. Logging only one would go quiet
 * on exactly the devices the browser fallback was built for.
 *
 * The result is truncated hard. This is a diagnostic breadcrumb — enough to see what AURA was
 * told and judge it — not an archive of page text, which the run trace already carries.
 */
class ResearchLogHook : PostToolHook, PostToolFailureHook {

    override suspend fun onPostTool(
        toolName: String,
        args: JsonObject,
        result: CallToolResult,
        ctx: HookContext,
    ) {
        val route = ROUTES[toolName] ?: return
        AgentLogger.Net.i(
            "research $route",
            mapOf(
                "tool" to toolName,
                "asked" to (asked(toolName, args) ?: "(none)"),
                "got" to resultText(result).take(MAX_RESULT_CHARS),
                "chars" to resultText(result).length,
            ),
        )
    }

    override suspend fun onPostToolFailure(
        toolName: String,
        args: JsonObject,
        result: CallToolResult,
        ctx: HookContext,
    ) {
        val route = ROUTES[toolName] ?: return
        // A failed lookup matters MORE than a successful one: it is the moment AURA carries on
        // without the answer it decided it needed, which is where a confident wrong step starts.
        AgentLogger.Net.w(
            "research $route FAILED",
            mapOf(
                "tool" to toolName,
                "asked" to (asked(toolName, args) ?: "(none)"),
                "error" to resultText(result).take(MAX_ERROR_CHARS),
            ),
        )
    }

    /** What was actually asked — the argument differs per route, the question does not. */
    private fun asked(toolName: String, args: JsonObject): String? =
        ASK_ARGS[toolName]
            ?.firstNotNullOfOrNull { key ->
                runCatching { args[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }
            ?.takeIf { it.isNotBlank() }

    private fun resultText(result: CallToolResult): String =
        result.content.filterIsInstance<TextContent>().mapNotNull { it.text }.joinToString("\n")

    private companion object {
        /** Tool → the label its line carries, so the two routes read alike in the log. */
        val ROUTES: Map<String, String> = mapOf(
            "web_search" to "SEARCH",
            "browser_open" to "BROWSE",
            "browser_read" to "READ",
            "browser_find" to "FIND",
        )

        /** Where each route keeps the question, checked in order. */
        val ASK_ARGS: Map<String, List<String>> = mapOf(
            "web_search" to listOf("query"),
            "browser_open" to listOf("url"),
            "browser_read" to listOf("session"),
            "browser_find" to listOf("query"),
        )

        const val MAX_RESULT_CHARS = 1_200
        const val MAX_ERROR_CHARS = 300
    }
}
