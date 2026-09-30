package com.aura.aura_ui.agent.mcpbridge.hooks

import com.aura.aura_ui.agent.state.AuraStateStore
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Records what the agent just did into [AuraStateStore], so the conversation plane can answer
 * "what are you doing?" and "why did that happen?" from fact rather than invention.
 *
 * Sits on the post-tool lanes because a tool that was *attempted* is what the user saw happen —
 * a failed tap still moved the screen state in their mind, and "that didn't work" is exactly the
 * follow-up this needs to be able to answer.
 *
 * Deliberately records the ACTION, not the result payload: results can be large and can contain
 * screen text, and this string is sent to Google as part of the Live prompt.
 */
class StateFeedHook : PostToolHook, PostToolFailureHook {

    override suspend fun onPostTool(
        toolName: String,
        args: JsonObject,
        result: CallToolResult,
        ctx: HookContext,
    ) = AuraStateStore.onAgentAction(describe(toolName, args))

    override suspend fun onPostToolFailure(
        toolName: String,
        args: JsonObject,
        result: CallToolResult,
        ctx: HookContext,
    ) = AuraStateStore.onAgentAction(describe(toolName, args) + " — which failed")

    /**
     * A short human phrase for one call. Only the few arguments that make an action recognisable
     * are included, and each is truncated: this is conversational grounding, not a log line.
     */
    private fun describe(toolName: String, args: JsonObject): String {
        val hint = ARG_HINTS.firstNotNullOfOrNull { key ->
            runCatching { args[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return if (hint != null) "$toolName \"${hint.take(MAX_HINT_CHARS)}\"" else toolName
    }

    private companion object {
        /** Checked in order; the first present one names the action. */
        val ARG_HINTS = listOf("text", "app_name", "query", "url", "element_description")
        const val MAX_HINT_CHARS = 60
    }
}
