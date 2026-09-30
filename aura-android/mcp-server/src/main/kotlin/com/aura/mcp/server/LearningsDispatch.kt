package com.aura.mcp.server

import com.aura.mcp.bridge.LearningsGateway
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Shared-memory dispatch glue (spec 2026-07-17), kept out of [scopedTool] so the
 * logic is unit-testable without an SDK transport. Everything here is fail-soft:
 * memory is never load-bearing, so a gateway failure must never break dispatch.
 */
internal object LearningsDispatch {

    /** Tools whose success means "we just entered an app" — the moment learned hints are relevant. */
    val HINT_TOOLS = setOf("launch_app", "open_deeplink")

    /** Valid `end_session` outcome values; anything else reads as absent. */
    private val OUTCOMES = setOf("success", "failure")

    /** Valid `goal_type` buckets (mirrors the app-side GoalClassifier's closed set). */
    val ALLOWED_GOAL_TYPES = setOf("play_media", "send_message", "open_app", "search", "navigate", "other")

    /** Contained gateway streaming — swallows gateway errors. */
    fun stream(
        gateway: LearningsGateway,
        toolName: String,
        args: JsonObject?,
        result: CallToolResult,
        failed: Boolean,
        clientLabel: String?,
    ) {
        runCatching { gateway.onToolExecuted(toolName, args, result, failed, clientLabel) }
    }

    /** The app a hint-tool call targets: `package_name` first, `app_name` fallback. */
    fun hintTargetApp(args: JsonObject?): String? =
        args?.get("package_name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: args?.get("app_name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Appends a `learned_hints` text block to a result; returns it unchanged for blank hints. */
    fun appendHints(result: CallToolResult, hints: String): CallToolResult =
        if (hints.isBlank()) {
            result
        } else {
            CallToolResult(
                content = result.content + TextContent(
                    buildJsonObject { put("learned_hints", hints) }.toString(),
                ),
                isError = result.isError,
            )
        }

    /**
     * Fetch-and-append for a successful hint-tool result. Fail-soft: any gateway
     * error returns the original result.
     */
    suspend fun withLearnedHints(
        gateway: LearningsGateway,
        toolName: String,
        args: JsonObject?,
        result: CallToolResult,
    ): CallToolResult {
        if (toolName !in HINT_TOOLS || result.isError == true) return result
        return runCatching {
            val app = hintTargetApp(args) ?: return result
            appendHints(result, gateway.hintsFor(app))
        }.getOrDefault(result)
    }

    /** Normalizes `end_session`'s outcome arg: only exact known values count. */
    fun parseOutcome(raw: String?): String? = raw?.takeIf { it in OUTCOMES }

    /** Normalizes `end_session`'s goal_type arg against the closed bucket set. */
    fun parseGoalType(raw: String?): String? = raw?.takeIf { it in ALLOWED_GOAL_TYPES }
}
