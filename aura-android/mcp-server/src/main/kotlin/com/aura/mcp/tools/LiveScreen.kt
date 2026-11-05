package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.bridge.UiTreeBridge
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One fresh read of the tree, for a tool checking what its own gesture did.
 *
 * - Contract: never throws; a failed read is an empty list, which callers report as "could not
 *   check", never as "it failed".
 * - Why not the perception cache: the cache is the screen the model saw; these checks are about
 *   the screen as it is now.
 */
internal object LiveScreen {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A text field's raw text, which [DetectedElement.label] does not keep (it falls back to the hint). */
    data class Field(val box: BBox, val text: String, val focused: Boolean)

    suspend fun payload(bridge: UiTreeBridge): String? =
        runCatching { bridge.snapshot() }.getOrNull()?.takeIf { it.ok }?.payloadJson

    suspend fun elements(bridge: UiTreeBridge): List<DetectedElement> =
        payload(bridge)?.let { UiTreeToElements.extract(it, minElements = 1) }.orEmpty()

    fun fields(payload: String): List<Field> {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return emptyList()
        return root["elements"]?.jsonArray.orEmpty().mapNotNull { raw ->
            val o = raw as? JsonObject ?: return@mapNotNull null
            if (o["isEditable"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
            val b = o["bounds"]?.jsonObject ?: return@mapNotNull null
            Field(
                BBox(
                    b["left"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    b["top"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    b["right"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    b["bottom"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                ),
                o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                o["isFocused"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }

    /**
     * What the field typed into now holds. Polls briefly until it holds [expected], because the
     * keyboard path commits asynchronously. Null when no field could be found.
     *
     * - Why four ways to find it: apps often do not mark the field focused, and typing can resize
     *   it (a search box makes room for its "clear" button), so the exact bounds of the look miss.
     *   In order: focused; same bounds; a field over the middle of [at]; the only field on screen.
     */
    suspend fun fieldText(bridge: UiTreeBridge, at: BBox?, expected: String): String? {
        var last: String? = null
        repeat(READ_BACK_POLLS) { i ->
            if (i > 0) delay(POLL_MS)
            val fields = payload(bridge)?.let(::fields).orEmpty()
            last = (
                fields.firstOrNull { it.focused }
                    ?: fields.firstOrNull { it.box == at }
                    ?: at?.let { a -> fields.firstOrNull { a.centerY in it.box.y1 until it.box.y2 && it.box.x1 < a.x2 && it.box.x2 > a.x1 } }
                    ?: fields.singleOrNull()
                )?.text
            if (last == expected) return last
        }
        return last
    }

    /** Password fields read back as a row of mask characters, which must not count as a mismatch. */
    fun masked(text: String): Boolean = text.isNotEmpty() && text.all { it in "•●*·" }

    /** Labels and places of everything inside [box]: equal before and after means nothing in it moved. */
    fun contentOf(elements: List<DetectedElement>, box: BBox): List<Pair<String, BBox>> =
        elements.filter { it.bbox.x1 >= box.x1 && it.bbox.y1 >= box.y1 && it.bbox.x2 <= box.x2 && it.bbox.y2 <= box.y2 }
            .map { it.label to it.bbox }

    private const val READ_BACK_POLLS = 3
    private const val POLL_MS = 120L
}
