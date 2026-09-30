package com.aura.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Helpers shared across every tool file in [com.aura.mcp.tools].
 *
 * Kept `internal` so they're visible inside the module but invisible to
 * `:app` consumers — tool-implementation details should not leak.
 */

internal fun numberSchema(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("number"),
        "description" to JsonPrimitive(description),
    )
)

internal fun stringSchema(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive(description),
    )
)

internal fun booleanSchema(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("boolean"),
        "description" to JsonPrimitive(description),
    )
)

/** Lenient boolean arg: accepts JSON true/false and "true"/"false" strings. */
internal fun JsonObject.boolArg(name: String): Boolean? =
    this[name]?.let { runCatching { it.jsonPrimitive.content.trim().lowercase().toBooleanStrictOrNull() }.getOrNull() }

internal fun jsonOk(success: Boolean, extra: Map<String, String>): CallToolResult {
    val payload = buildJsonObject {
        put("success", success)
        for ((k, v) in extra) put(k, v)
    }
    return CallToolResult(
        content = listOf(TextContent(payload.toString())),
        isError = !success,
    )
}

internal fun jsonOkPayload(payload: JsonObject, success: Boolean = true): CallToolResult =
    CallToolResult(content = listOf(TextContent(payload.toString())), isError = !success)

/**
 * P1: resolution outcome for som-targeting gesture tools — either dispatchable
 * full-res coordinates or a ready-made, model-actionable error result.
 */
internal sealed interface SomResolveOutcome {
    data class Coords(val x: Int, val y: Int) : SomResolveOutcome
    data class Error(val result: CallToolResult) : SomResolveOutcome
}

/**
 * P1: resolve a som_id with staleness enforcement, shared by every som-targeting
 * gesture tool. Stale and unknown ids each get a distinct error message; the fix
 * for both is always the same — perceive_screen again.
 */
internal suspend fun com.aura.mcp.cache.PerceptionCache.resolveForGesture(somId: Int): SomResolveOutcome =
    when (val r = resolve(somId)) {
        is com.aura.mcp.cache.PerceptionCache.Resolution.Fresh -> {
            // Diagnostic only — never blocks or alters the gesture. A gesture
            // landing on a box with no label is worth counting: it's the
            // signature of the "clickable wrapper, no text of its own" pattern
            // (see UiTreeToElements' descendant label borrowing) slipping
            // through anyway. Not auto-escalated — one instance proves nothing;
            // a pattern across many runs is what would justify a behavior change.
            if (hasLabel(somId) == false) {
                android.util.Log.w(
                    "PerceptionCache",
                    "som_id $somId dispatched with no label at perceive time",
                )
            }
            // The box, for the run log's "what was tapped" crop. Never in the result the model reads.
            com.aura.mcp.server.currentTrail()?.step(
                "target", somId.toString(), "info",
                boundsFor(somId)?.let { "${it.x1},${it.y1},${it.x2},${it.y2}" } ?: "${r.x},${r.y},${r.x},${r.y}",
            )
            SomResolveOutcome.Coords(r.x, r.y)
        }
        // "is STALE:" is matched by the app's ScreenAfterAction.STALE_TAG, which re-reads the
        // screen for the agent after this refusal. Change both together.
        is com.aura.mcp.cache.PerceptionCache.Resolution.Stale -> SomResolveOutcome.Error(
            errorResult(
                "som_id $somId is STALE: ${r.reason}. " +
                    "Call read_screen and pick a som_id from the fresh result.",
            ),
        )
        is com.aura.mcp.cache.PerceptionCache.Resolution.Unknown -> SomResolveOutcome.Error(
            errorResult(
                "som_id $somId not found in perception cache. " +
                    "Cached som_ids: ${cachedSomIds()}. " +
                    "Call read_screen to refresh the element list, then pick a valid som_id.",
            ),
        )
    }
