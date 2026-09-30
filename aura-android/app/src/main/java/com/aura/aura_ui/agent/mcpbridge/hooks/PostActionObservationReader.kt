package com.aura.aura_ui.agent.mcpbridge.hooks

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared reader for the E2 `post_action_observation` block that the server appends to every
 * screen-mutating WRITE tool result (settled foreground app, screen_changed, element count…).
 * Consumed by LearningsWriteHook (recovery lessons, M4 attribution) and LedgerUpdateHook
 * (run-ledger step records) — one parser, one shape.
 */
object PostActionObservationReader {
    const val OBSERVATION_KEY = "post_action_observation"

    private val json = Json { ignoreUnknownKeys = true }

    /** The raw observation object, or null when the result carries no bundle. */
    fun observation(result: CallToolResult): JsonObject? =
        result.content.asSequence()
            .filterIsInstance<TextContent>()
            .mapNotNull { it.text }
            .filter { OBSERVATION_KEY in it }
            .mapNotNull { text ->
                runCatching {
                    json.parseToJsonElement(text).jsonObject[OBSERVATION_KEY]?.jsonObject
                }.getOrNull()
            }
            .firstOrNull()

    /** E2 `screen_changed`, or null when no bundle / no signal. */
    fun screenChanged(observation: JsonObject?): Boolean? =
        observation?.get("screen_changed")?.jsonPrimitive?.booleanOrNull

    /** Settled `foreground_app` package, or null when no bundle / blank. */
    fun foregroundApp(observation: JsonObject?): String? =
        observation?.get("foreground_app")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Up to [max] settled `top_labels`, for naming a screen in a sentence. Never null. */
    fun topLabels(observation: JsonObject?, max: Int): List<String> =
        (observation?.get("top_labels") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { l -> l.isNotBlank() } }
            ?.distinct()
            ?.take(max)
            .orEmpty()

    /** First settled `top_labels` entry — the landed screen's headline label, or null. */
    fun topLabel(observation: JsonObject?): String? =
        (observation?.get("top_labels") as? JsonArray)
            ?.firstOrNull()
            ?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
}
