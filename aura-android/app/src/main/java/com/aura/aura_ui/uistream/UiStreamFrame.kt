package com.aura.aura_ui.uistream

import org.json.JSONArray
import org.json.JSONObject

/**
 * One line of the stream: the whole screen, as NDJSON.
 *
 * Pure and Android-free (bar `org.json`, which is on the JVM test classpath via
 * Robolectric-free `json` in unit tests) so the wire format is unit-testable without a
 * device. [UiStreamServer] owns the socket; this owns the bytes.
 *
 * ### Shape
 *
 * ```
 * {"v":1,"seq":42,"ts":1787030000000,"idle":true,"quiet_ms":812,"changed":true,
 *  "pkg":"com.whatsapp","w":1240,"h":2772,"n":181,
 *  "e":[[x1,y1,x2,y2,"flags","label","Class"], ...]}
 * ```
 *
 * Elements are POSITIONAL arrays, not objects. The alternative — repeating sixteen key
 * names per element the way `AccessibilityDataModels.toTreeMap` does — measured ~19k
 * tokens for one Amazon screen against ~0.7k for this shape. At four frames a second
 * that difference is the whole feature.
 *
 * `som_id` is deliberately absent: it is the element's INDEX in `e` (first entry is 1),
 * the same invariant `perceive_screen` relies on. Nothing may filter `e` downstream
 * without renumbering, or ids silently desynchronise.
 *
 * ### The agent-facing sibling
 *
 * `ScreenFrame` in `:mcp-server` (`mcp/tools/ScreenFrame.kt`) is this format ported for
 * the `read_screen` tool. It differs in two ways, both deliberate: elements are ordered
 * `[bounds, label, flags, class]` to match `perceive_screen`'s label-before-flags `e`
 * (the agent reads both formats, and two positional orders is a misread risk a viewer
 * never faces), and its labels come from `UiTreeToElements`' recovery chain rather than
 * the node's own `text`. Keep the two in step when either changes.
 *
 * ### Flags
 * `*` clickable · `e` editable · `S` scrollable · `c`/`o` checked/unchecked ·
 * `d` disabled · `l` long-clickable. Empty string when none apply.
 */
object UiStreamFrame {

    const val VERSION = 1

    /** Sent once when a client connects, so a consumer can self-describe. */
    fun hello(quietMs: Long, intervalMs: Long): String = JSONObject().apply {
        put("v", VERSION)
        put("type", "hello")
        put("quiet_ms_threshold", quietMs)
        put("interval_ms", intervalMs)
        put(
            "element_shape",
            JSONArray(listOf("x1", "y1", "x2", "y2", "flags", "label", "class")),
        )
        put("som_id", "1-based index into e")
        put(
            "note",
            "Frames stream continuously, including mid-animation. Gate on idle=true " +
                "before acting on one.",
        )
    }.toString()

    /**
     * Build one frame from an extractor tree map (`UITreeExtractor.getUITree()`).
     * Returns null when the tree carries nothing usable — callers emit a `stale`
     * frame instead of a lie.
     */
    fun of(
        tree: Map<*, *>,
        seq: Long,
        nowMs: Long,
        idle: Boolean,
        quietMs: Long,
        changed: Boolean,
    ): String? {
        val elements = tree["elements"] as? List<*> ?: return null
        val arr = JSONArray()
        for (raw in elements) {
            val e = raw as? Map<*, *> ?: continue
            val b = e["bounds"] as? Map<*, *> ?: continue
            val x1 = (b["left"] as? Number)?.toInt() ?: continue
            val y1 = (b["top"] as? Number)?.toInt() ?: continue
            val x2 = (b["right"] as? Number)?.toInt() ?: continue
            val y2 = (b["bottom"] as? Number)?.toInt() ?: continue
            if (x2 <= x1 || y2 <= y1) continue
            val text = (e["text"] as? String).orEmpty().ifBlank {
                (e["contentDescription"] as? String).orEmpty()
            }
            arr.put(
                JSONArray().apply {
                    put(x1); put(y1); put(x2); put(y2)
                    put(flagsOf(e))
                    put(text.replace(Regex("\\s+"), " ").trim())
                    put((e["className"] as? String).orEmpty().substringAfterLast('.'))
                },
            )
        }
        return JSONObject().apply {
            put("v", VERSION)
            put("type", "frame")
            put("seq", seq)
            put("ts", nowMs)
            put("idle", idle)
            put("quiet_ms", quietMs)
            put("changed", changed)
            put("pkg", (tree["package_name"] as? String).orEmpty())
            put("w", (tree["screen_width_px"] as? Number)?.toInt() ?: 0)
            put("h", (tree["screen_height_px"] as? Number)?.toInt() ?: 0)
            put("n", arr.length())
            put("truncated", tree["truncated"] == true)
            (tree["offscreen_text"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let {
                put("offscreen", JSONArray(it.map(Any?::toString)))
            }
            put("e", arr)
        }.toString()
    }

    /**
     * Emitted when the tree could not be read or came back empty. A consumer must be
     * able to tell "the screen has nothing on it" from "we could not look" — a silent
     * gap would read as an empty screen, which is the failure mode this whole stream
     * exists to make visible.
     */
    fun stale(seq: Long, nowMs: Long, idle: Boolean, quietMs: Long, reason: String): String =
        JSONObject().apply {
            put("v", VERSION)
            put("type", "stale")
            put("seq", seq)
            put("ts", nowMs)
            put("idle", idle)
            put("quiet_ms", quietMs)
            put("reason", reason)
        }.toString()

    private fun flagsOf(e: Map<*, *>): String = buildString {
        if (e["isEditable"] == true) append('e')
        when (e["isCheckable"]) {
            true -> append(if (e["isChecked"] == true) 'c' else 'o')
            else -> Unit
        }
        if (e["isEnabled"] == false) append('d')
        if (e["isScrollable"] == true) append('S')
        if (e["isLongClickable"] == true) append('l')
        if (e["isClickable"] == true) append('*')
    }
}
