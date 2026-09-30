package com.aura.mcp.tools

import com.aura.mcp.bridge.DetectedElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The `read_screen` wire format: the whole screen as one compact JSON object.
 *
 * ### Why positional arrays
 *
 * The retired `get_ui_tree` returned the tree with every key name repeated per
 * element — sixteen of them, measured at ~19k tokens for one Amazon screen. That is
 * the entire reason it was cheap in latency and unusable in practice: the doctrine
 * correctly steered away from it, so the cheap perception path effectively did not
 * exist. Dropping the key names takes the same screen to ~0.7k.
 *
 * Ported from `com.aura.aura_ui.uistream.UiStreamFrame`, which established this shape
 * for the desktop viewer, with two deliberate departures:
 *
 *  1. **Label before flags.** `UiStreamFrame` orders `[bounds, flags, label, class]`,
 *     but `perceive_screen`'s `e` is `[cx, cy, name, flags]` — label first. Two
 *     positional orders in one agent's context is a live misread risk, and the agent
 *     reads both, so this one matches `perceive_screen`.
 *  2. **Elements come from [UiTreeToElements], not from the raw tree.** That parser
 *     already owns the label-recovery chain (own text → contentDescription → borrowed
 *     from a contained descendant → viewId → class name) which took nameless boxes on
 *     the measured Amazon home screen from 22-of-49 to zero. Re-reading `text` straight
 *     off the node the way the viewer does would ship those boxes nameless again.
 *
 * ### som_id
 *
 * Deliberately absent as a field: a som_id IS the element's index in `e`, first entry
 * is 1 — the same invariant `perceive_screen` publishes, so both producers agree about
 * what "12" means. [encode] sorts by [DetectedElement.somId] to guarantee it. Nothing
 * may filter or reorder `e` downstream without renumbering.
 *
 * ### Shape
 *
 * ```
 * {"v":1,"pkg":"com.whatsapp","w":1240,"h":2772,"n":181,"idle":true,"settled":true,
 *  "e":[[x1,y1,x2,y2,"label","flags","Class"], ...]}
 * ```
 *
 * Flags: `*` clickable · `e` editable · `S` scrollable · `c`/`o` checked/unchecked ·
 * `d` disabled · `l` long-clickable. Empty string when none apply.
 *
 * Pure — data in, string out. No Android types, no I/O, no coroutines, so the wire
 * format is unit-testable under plain-JVM `:mcp-server:test`.
 */
internal object ScreenFrame {

    const val VERSION = 1

    /** Screen-level facts that live outside the element list. */
    data class Meta(val pkg: String, val widthPx: Int, val heightPx: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Read [Meta] off the opaque tree payload from `UiTreeBridge.snapshot()`.
     *
     * Degrades to empty/zero rather than throwing: `read_screen` still owes the agent
     * an answer on a malformed payload, and a frame with `pkg:""` is honest about what
     * it does not know, where an exception would just cost a turn.
     */
    fun metaOf(payloadJson: String): Meta {
        val root: JsonObject = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: return Meta("", 0, 0)
        return Meta(
            pkg = root["package_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            widthPx = root["screen_width_px"]?.jsonPrimitive?.intOrNull ?: 0,
            heightPx = root["screen_height_px"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /**
     * Encode one settled screen.
     *
     * @param idle whether the screen was standing still when read.
     * @param settled false only when the settle cap cut a still-moving screen short —
     *   reported rather than hidden, so a frame taken mid-transition is never handed
     *   over labelled good. A partial tree looks exactly like a complete one.
     * @param escalateReason non-null when the tree read fine but described almost
     *   nothing (WebView / Canvas / game). Adds the `escalate` pair that tells the
     *   agent to reach for `perceive_screen` instead of acting on a thin list.
     */
    fun encode(
        elements: List<DetectedElement>,
        meta: Meta,
        idle: Boolean,
        settled: Boolean,
        escalateReason: String? = null,
        offscreen: List<String> = emptyList(),
    ): String = buildJsonObject {
        put("v", VERSION)
        put("pkg", meta.pkg)
        put("w", meta.widthPx)
        put("h", meta.heightPx)
        put("n", elements.size)
        // Every element here came from the accessibility tree — there is no CV pass in
        // this tool — so the tree count IS the element count. Emitted because
        // ActionGuard's loop detection keys on it: it reads `ui_tree_count` to find the
        // deterministic prefix of a grounding result, and without the field it falls
        // back to hashing the whole payload, where `idle` flipping between two looks at
        // ONE screen reads as two different screens and no loop is ever detected.
        put("ui_tree_count", elements.size)
        put("idle", idle)
        put("settled", settled)
        if (escalateReason != null) {
            put("escalate", "perceive_screen")
            put("escalate_reason", escalateReason)
        }
        // Text that EXISTS but is not on screen — the rest of a carousel or a
        // sideways row. Carried because the post-scroll hint sends the agent here to
        // decide whether it has reached the end, and that question is unanswerable
        // from the visible elements alone. No som_id and no coordinates: these cannot
        // be tapped, only read.
        if (offscreen.isNotEmpty()) {
            putJsonArray("offscreen") { offscreen.forEach { add(it) } }
        }
        putJsonArray("e") {
            // Sorted, not filtered: index must equal som_id, and a gap would
            // silently shift every later element's identity.
            for (el in elements.sortedBy { it.somId }) {
                addJsonArray {
                    add(el.bbox.x1)
                    add(el.bbox.y1)
                    add(el.bbox.x2)
                    add(el.bbox.y2)
                    add(el.label.replace(WHITESPACE, " ").trim())
                    add(flagsOf(el))
                    add(el.elementType)
                }
            }
        }
    }.toString()

    /**
     * One character per actionable property, in a fixed order so the string is
     * comparable across frames.
     *
     * `checked` is tri-state on [DetectedElement] — null means "has no on/off state",
     * which must stay distinguishable from "off", or the agent turns a setting off
     * while trying to turn it on.
     */
    private fun flagsOf(el: DetectedElement): String = buildString {
        if (el.editable) append('e')
        when (el.checked) {
            true -> append('c')
            false -> append('o')
            null -> Unit
        }
        if (!el.enabled) append('d')
        if (el.scrollable) append('S')
        if (el.longClickable) append('l')
        if (el.interactive) append('*')
    }
}
