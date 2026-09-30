package com.aura.aura_ui.agent.mcpbridge

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * O1/O3: derives the truthful outcome of a completed tool call for the run log and
 * the overlay UI. MCP failures never throw — `McpTool`/`ToolHookChain` deliberately
 * return `CallToolResult(isError=true)` (denied gestures, failed taps, guard blocks) —
 * so Koog's `onToolCallFailed` never fires for them and success must be read from the
 * result itself, not hardcoded.
 *
 * Two lanes, same rules:
 *  - **typed** — the result IS a [CallToolResult];
 *  - **JSON-encoded** — on-device, Koog's `onToolCallCompleted` carries the JSON
 *    rendering of the result instead of the instance. Before this lane existed the
 *    `as?` cast fell through and every denied gesture was logged as a success
 *    (observed live: tap denials with success=True dur=2ms).
 *
 * The summary renders text content only; image/audio parts are elided to a short
 * placeholder so a perceive_screen result never drags hundreds of KB of base64 into
 * the run log (O3). Pure — unit-tested in `ToolCallOutcomeTest`.
 */
internal object ToolCallOutcome {

    data class Outcome(val success: Boolean, val summary: String)

    fun from(toolResult: Any?): Outcome {
        (toolResult as? CallToolResult)?.let { call ->
            val summary = call.content.joinToString("\n") { part ->
                when (part) {
                    is TextContent -> part.text
                    else -> "[${part::class.simpleName ?: "content"} elided]"
                }
            }.trim()
            return Outcome(success = call.isError != true, summary = summary)
        }
        val raw = toolResult?.toString().orEmpty()
        return decodeEncodedResult(raw)
            ?: decodeLoosely(raw)
            ?: Outcome(success = true, summary = raw)
    }

    /**
     * Last resort before "assume success": the envelope rendering that is NOT valid JSON.
     *
     * This lane is the one that actually runs on device. The rendering embeds each tool's JSON
     * as an unescaped string, so [decodeEncodedResult]'s strict parse always returned null and
     * every call fell through to the hardcoded `success = true` below — a failed `press_enter`
     * (`"isError":true`) was logged, and drawn in the trace, as a green ✓. The raw envelope also
     * carried the perceive screenshot's base64 straight into the run log.
     *
     * See [McpEnvelopeText] for why this scans rather than parses.
     */
    private fun decodeLoosely(raw: String): Outcome? {
        val env = McpEnvelopeText.parse(raw) ?: return null
        val summary = env.parts.joinToString("\n") { part ->
            if (part.isBinary) "[image elided — ~${part.approxBytes / 1024} KB]" else part.text
        }.trim()
        return Outcome(success = env.isError != true, summary = summary)
    }

    /**
     * Parse the JSON rendering of a `CallToolResult` envelope. Returns null for
     * anything that is not one (the caller falls back to the raw string).
     */
    private fun decodeEncodedResult(raw: String): Outcome? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        val obj = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return null
        val content = obj["content"] as? JsonArray ?: return null

        val summary = content.joinToString("\n") { part ->
            val p = part as? JsonObject ?: return@joinToString "[content elided]"
            val type = (p["type"] as? JsonPrimitive)?.contentOrNull
            val text = (p["text"] as? JsonPrimitive)?.contentOrNull
            if (type == "text" && text != null) text else "[${type ?: "content"} elided]"
        }.trim()

        val isError = (obj["isError"] as? JsonPrimitive)?.booleanOrNull
        return Outcome(success = isError != true, summary = summary)
    }
}
