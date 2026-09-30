package com.aura.mcp.tools

import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 9 — `verify_action`: post-gesture evidence collector.
 *
 * Captures a fresh UI tree snapshot, extracts a compact summary
 * (foreground app, element count, top labels, keyboard flag, loading
 * flag), and echoes the caller's `expected` string back so the audit
 * log records intent alongside outcome.
 *
 * The server **does not** make a yes/no verdict — that is the calling
 * AI's job. We supply ground-truth evidence; the AI evaluates it
 * against `expected`. Keeping evidence and judgement on different
 * sides of the wire is what makes this composable with the AI's own
 * planning logic.
 *
 * Maps to: legacy `VerifierAgent` in the Python pipeline.
 */
internal fun Server.registerVerifyActionTool(uiTreeBridge: UiTreeBridge) {
    scopedTool(
        name = "verify_action",
        // Was 164 tok and told the agent to "call immediately after any gesture" — which
        // directly contradicted the doctrine (every gesture already returns
        // post_action_observation, so verify from THAT). A tool description that argues with
        // the system prompt is worse than no description: the model has to pick a winner, and
        // it was never told which. Now it states the one case the observation cannot cover.
        description = "Read the current state without acting: foreground app, element count, top labels, " +
            "keyboard, loading. Only for a screen that may have changed on its own (a notification, a " +
            "refresh); every action already returns this.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "expected" to stringSchema(
                        "Plain-English description of what should now be visible / true, " +
                            "e.g. 'Spotify Home tab with playlist list visible'. " +
                            "Echoed back for audit logging.",
                    ),
                ),
            ),
            required = listOf("expected"),
        ),
    ) { request ->
        val expected = request.arguments?.stringArg("expected")
            ?: return@scopedTool errorResult("verify_action requires an 'expected' argument")

        val snapshot = uiTreeBridge.snapshot()
        if (!snapshot.ok) {
            return@scopedTool errorResult("UI tree snapshot failed")
        }

        val summary = UiTreeHeuristics.summarize(snapshot.payloadJson)

        val payload = buildJsonObject {
            put("expected", expected)
            put("foreground_app", summary.foregroundApp)
            put("element_count", summary.elementCount)
            put("keyboard_visible", summary.keyboardVisible)
            put("loading_indicator_present", summary.loadingIndicatorPresent)
            put("top_labels", buildLabelsArray(summary.topLabels))
            put(
                "hint",
                "Compare these signals against 'expected'. If they match, proceed. " +
                    "If they don't, decide whether to retry, re-perceive, or stop.",
            )
        }
        CallToolResult(content = listOf(TextContent(payload.toString())))
    }
}

private fun buildLabelsArray(labels: List<String>): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.buildJsonArray {
        labels.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }
