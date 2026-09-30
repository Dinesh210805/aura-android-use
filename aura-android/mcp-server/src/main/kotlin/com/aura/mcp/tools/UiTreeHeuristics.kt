package com.aura.mcp.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 9 — shared parser for the opaque UI-tree JSON the
 * [com.aura.mcp.bridge.UiTreeBridge.snapshot] returns.
 *
 * Used by both [registerVerifyActionTool] and [registerWaitForTool] so the
 * two tools agree on what "loading" looks like and on which fields make it
 * into the summary.
 *
 * Schema produced by `AuraAccessibilityService.uiTreeExtractor` (as of
 * 2026-05):
 *   {
 *     "package_name": String,
 *     "elements_count": Int,
 *     "elements": [
 *       {
 *         "text": String,
 *         "contentDescription": String,
 *         "className": String,
 *         "isClickable": Boolean,
 *         "isEditable": Boolean,
 *         "isFocused": Boolean,
 *         ...
 *       }
 *     ]
 *   }
 *
 * Parsing is best-effort — if the schema drifts, [summarize] degrades to
 * zeros / empties rather than throwing. The caller still gets a usable
 * (if minimal) response and the AI can fall back to read_screen directly.
 */
internal object UiTreeHeuristics {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Summary(
        val foregroundApp: String,
        val elementCount: Int,
        val keyboardVisible: Boolean,
        val loadingIndicatorPresent: Boolean,
        val topLabels: List<String>,
        val screenHeightPx: Int = 0,
    )

    fun summarize(payloadJson: String, maxLabels: Int = 10): Summary {
        val root: JsonObject = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: return EMPTY

        val foregroundApp = root["package_name"]?.jsonPrimitive?.content.orEmpty()
        val elementCount = root["elements_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val screenHeightPx = root["screen_height_px"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val elements: JsonArray = root["elements"]?.jsonArray ?: JsonArray(emptyList())

        var keyboardVisible = false
        var loadingPresent = false
        val labels = mutableListOf<String>()

        for (element in elements) {
            val obj = element as? JsonObject ?: continue

            val className = obj.stringField("className")
            val text = obj.stringField("text")
            val contentDescription = obj.stringField("contentDescription")
            val isFocused = obj["isFocused"]?.jsonPrimitive?.booleanOrNull == true
            val isEditable = obj["isEditable"]?.jsonPrimitive?.booleanOrNull == true
            val isClickable = obj["isClickable"]?.jsonPrimitive?.booleanOrNull == true

            if (!keyboardVisible && isFocused && isEditable) {
                keyboardVisible = true
            }
            if (!loadingPresent && isLoadingIndicator(className, text, contentDescription)) {
                loadingPresent = true
            }
            if (labels.size < maxLabels && isClickable) {
                val label = text.ifBlank { contentDescription }
                if (label.isNotBlank()) labels.add(label)
            }
        }

        return Summary(
            foregroundApp = foregroundApp,
            elementCount = elementCount,
            keyboardVisible = keyboardVisible,
            loadingIndicatorPresent = loadingPresent,
            topLabels = labels,
            screenHeightPx = screenHeightPx,
        )
    }

    private fun JsonObject.stringField(name: String): String =
        get(name)?.jsonPrimitive?.contentOrEmpty() ?: ""

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrEmpty(): String =
        runCatching { content }.getOrDefault("")

    private fun isLoadingIndicator(className: String, text: String, contentDesc: String): Boolean {
        if (className.contains("ProgressBar", ignoreCase = true)) return true
        val combined = "$text $contentDesc"
        return combined.contains("Loading", ignoreCase = true) ||
            combined.contains("Please wait", ignoreCase = true)
    }

    private val EMPTY = Summary(
        foregroundApp = "",
        elementCount = 0,
        keyboardVisible = false,
        loadingIndicatorPresent = false,
        topLabels = emptyList(),
    )
}
