package com.aura.mcp.tools

import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 9 — `wait_for`: poll until loading heuristics pass or timeout.
 *
 * Maps to: legacy RSG `wait` subgoal observed in the analyzed Python log.
 *
 * Threading: uses coroutine `delay()`, **never** `Thread.sleep()`. The MCP
 * SDK dispatches tool handlers on Ktor's request worker — blocking the
 * thread would block other requests on the same engine.
 *
 * The server doesn't "understand" `condition` — it's logged for audit and
 * echoed back so the AI re-evaluates final state against it.
 */
internal fun Server.registerWaitForTool(uiTreeBridge: UiTreeBridge) {
    scopedTool(
        name = "wait_for",
        description = "Wait for slow content — a download, upload, processing, a ride or order status — until " +
            "loading indicators clear or timeout_ms passes (default 5000, max 30000). Not needed " +
            "after taps, typing or launching; those already wait for the screen. Returns the state; " +
            "you judge whether it matches condition.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "condition" to stringSchema(
                        "Plain-English description of what you are waiting for, " +
                            "e.g. 'Spotify Library tab loaded with playlist list visible'. " +
                            "Echoed back for audit.",
                    ),
                    "timeout_ms" to numberSchema(
                        "Maximum total wait in milliseconds (default 5000, max 30000)",
                    ),
                    "poll_interval_ms" to numberSchema(
                        "Time between UI tree polls in milliseconds (default 500, min 100)",
                    ),
                ),
            ),
            required = listOf("condition"),
        ),
    ) { request ->
        val args = request.arguments
        val condition = args?.stringArg("condition")
            ?: return@scopedTool errorResult("wait_for requires a 'condition' argument")

        val timeoutMs = (args.intArg("timeout_ms") ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val pollIntervalMs = (args.intArg("poll_interval_ms") ?: DEFAULT_POLL_MS)
            .coerceAtLeast(MIN_POLL_MS)

        val started = System.currentTimeMillis()
        var lastSummary: UiTreeHeuristics.Summary = UiTreeHeuristics.summarize("{}")
        var loadingCleared = false
        var pollCount = 0

        while (System.currentTimeMillis() - started < timeoutMs) {
            pollCount++
            val snapshot = uiTreeBridge.snapshot()
            if (snapshot.ok) {
                lastSummary = UiTreeHeuristics.summarize(snapshot.payloadJson)
                if (!lastSummary.loadingIndicatorPresent && lastSummary.elementCount > 0) {
                    loadingCleared = true
                    break
                }
            }
            delay(pollIntervalMs.toLong())
        }

        val elapsed = System.currentTimeMillis() - started

        val payload = buildJsonObject {
            put("condition", condition)
            put("elapsed_ms", elapsed)
            put("poll_count", pollCount)
            put("timed_out", !loadingCleared)
            put("foreground_app", lastSummary.foregroundApp)
            put("element_count", lastSummary.elementCount)
            put("loading_indicator_present", lastSummary.loadingIndicatorPresent)
            put("keyboard_visible", lastSummary.keyboardVisible)
            put(
                "top_labels",
                buildJsonArray {
                    lastSummary.topLabels.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                },
            )
            put(
                "hint",
                if (loadingCleared) {
                    "Loading heuristics passed. Call read_screen for fresh som_ids before tapping."
                } else {
                    "Timed out. Final state may not match your `condition` — decide " +
                        "whether to extend the wait, retry the trigger gesture, or stop."
                },
            )
        }
        CallToolResult(content = listOf(TextContent(payload.toString())))
    }
}

private const val DEFAULT_TIMEOUT_MS = 5_000
private const val MIN_TIMEOUT_MS = 100
private const val MAX_TIMEOUT_MS = 30_000
private const val DEFAULT_POLL_MS = 500
private const val MIN_POLL_MS = 100
