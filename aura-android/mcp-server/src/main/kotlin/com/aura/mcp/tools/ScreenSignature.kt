package com.aura.mcp.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Layer 1 of screen-change detection — the **authority** on screen identity.
 *
 * Hashes an accessibility-tree snapshot into two numbers. Where
 * [com.aura.mcp.cache.ScreenActivity] can only say "something may have happened",
 * comparing signatures says whether the screen is genuinely different — which is the
 * question every consumer actually has:
 *
 *  - `perceive_screen`: is a cached CV result still valid for this screen?
 *  - gesture tools: are my cached som_id coordinates still pointing at reality?
 *  - post-action observation: did the tap I just dispatched change anything?
 *  - tier policy: which screen am I scoring the accessibility tree's trust for?
 *
 * Two hashes, computed in ONE pass because they share all the expensive work:
 *
 *  - [Signature.layout] — structure only: class name, resource id, quantized bounds,
 *    interactivity. Stable across "same screen, different data", so it is the right
 *    key for per-screen trust and for memoizing CV output.
 *  - [Signature.content] — layout PLUS text and checked state. This is the
 *    change detector: a toggle flipping or a label changing must register.
 *
 * Bounds are quantized to [BOUNDS_QUANTUM] px so a signature does not churn on
 * animation frames — a screen sliding into place must not read as a new screen every
 * frame. This is the detail the model-based Android testing literature (Stoat,
 * DroidBot, APE) converged on independently for exactly the same reason.
 *
 * Pure: JSON in, two longs out. No Android types, no coroutines, no I/O — runs under
 * plain-JVM tests and costs ~1 ms on a real tree (a few hundred nodes) over a snapshot
 * the caller had already paid for.
 */
internal object ScreenSignature {

    /** Bounds grid, in device px. Coarse enough to absorb animation jitter and
     * sub-pixel layout drift, fine enough that two different layouts collide only
     * if they genuinely occupy the same cells. */
    const val BOUNDS_QUANTUM = 8

    /** Label bytes folded into the content hash per node — enough to notice a real
     * text change without walking long paragraphs on text-dense screens. */
    const val LABEL_HASH_CHARS = 24

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Signature(
        /** Structure-only hash. Equal ⇒ same screen layout, possibly new data. */
        val layout: Long,
        /** Structure + text + toggle state. Equal ⇒ nothing visible changed. */
        val content: Long,
        val packageName: String,
        val nodeCount: Int,
        /**
         * True when the tree could not describe this screen (absent, error, or
         * `validation_failed` — WebView / Canvas / game surfaces). Both hashes are
         * meaningless here: the tree stays constant while the screen moves freely,
         * so callers MUST NOT read "signature unchanged" as "screen unchanged".
         * Fall back to a pixel-level check or to raw event activity instead.
         */
        val treeBlind: Boolean,
    ) {
        /** Same layout AND same data — nothing the user could see has changed. */
        fun sameContentAs(other: Signature?): Boolean =
            other != null && !treeBlind && !other.treeBlind &&
                content == other.content && packageName == other.packageName

        /** Same screen, data may differ — the right notion for trust and CV reuse. */
        fun sameLayoutAs(other: Signature?): Boolean =
            other != null && !treeBlind && !other.treeBlind &&
                layout == other.layout && packageName == other.packageName
    }

    /** Signature of a tree that told us nothing. Never equal to any other. */
    fun blind(packageName: String = ""): Signature =
        Signature(layout = 0L, content = 0L, packageName = packageName, nodeCount = 0, treeBlind = true)

    /**
     * Hash [payloadJson] — the opaque tree snapshot from
     * [com.aura.mcp.bridge.UiTreeBridge.snapshot]. Malformed or validation-failed
     * input yields a [blind] signature rather than throwing; a change detector that
     * crashes on a weird screen is worse than one that admits it cannot tell.
     */
    fun of(payloadJson: String): Signature {
        val root: JsonObject = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: return blind()

        val packageName = root["package_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (root["validation_failed"]?.jsonPrimitive?.booleanOrNull == true) return blind(packageName)
        val elements: JsonArray = root["elements"]?.jsonArray ?: return blind(packageName)

        var layout = FNV_OFFSET
        var content = FNV_OFFSET
        var counted = 0

        layout = layout.mix(packageName)
        content = content.mix(packageName)

        for (raw in elements) {
            val obj = raw as? JsonObject ?: continue
            val bounds = obj["bounds"]?.jsonObject ?: continue
            val left = bounds.int("left") ?: continue
            val top = bounds.int("top") ?: continue
            val right = bounds.int("right") ?: continue
            val bottom = bounds.int("bottom") ?: continue
            // Degenerate boxes are dropped by every downstream consumer already
            // (see UiTreeToElements); including them here would let an off-screen
            // node flap the signature without ever being actionable.
            if (right <= left || bottom <= top) continue

            counted++

            // ── layout lane: what the screen IS ──
            // `viewId` is the key UITreeExtractor actually emits (it holds
            // AccessibilityNodeInfo.viewIdResourceName). Pinned by
            // UiElementSerializationTest's required-keys list; the fallback covers a
            // producer that names it after the platform getter instead.
            val viewId = obj.str("viewId").ifBlank { obj.str("viewIdResourceName") }
            layout = layout
                .mix(obj.str("className").substringAfterLast('.'))
                .mix(viewId.substringAfterLast('/'))
                .mix(left / BOUNDS_QUANTUM)
                .mix(top / BOUNDS_QUANTUM)
                .mix(right / BOUNDS_QUANTUM)
                .mix(bottom / BOUNDS_QUANTUM)
                .mix(interactivityBits(obj))

            // ── content lane: layout PLUS what it currently says/holds ──
            content = content
                .mix(layout)
                .mix(obj.str("text").take(LABEL_HASH_CHARS))
                .mix(obj.str("contentDescription").take(LABEL_HASH_CHARS))
                .mix(if (obj.bool("isChecked")) 1 else 0)
        }

        // A tree with valid JSON but no usable nodes is blind in every way that
        // matters — do not hand back a stable hash that says "screen unchanged".
        if (counted == 0) return blind(packageName)

        return Signature(
            layout = layout,
            content = content,
            packageName = packageName,
            nodeCount = counted,
            treeBlind = false,
        )
    }

    /**
     * Interactivity folded into one int. Included in the LAYOUT lane because a node
     * becoming clickable (a button enabling, a form completing) restructures what the
     * agent can do even when nothing moved a pixel.
     */
    private fun interactivityBits(obj: JsonObject): Int {
        var bits = 0
        if (obj.bool("isClickable")) bits = bits or 0x01
        if (obj.bool("isScrollable")) bits = bits or 0x02
        if (obj.bool("isEditable")) bits = bits or 0x04
        if (obj.bool("isCheckable")) bits = bits or 0x08
        if (obj.bool("isLongClickable")) bits = bits or 0x10
        // Absent means enabled — older payloads omit the field entirely.
        if (obj["isEnabled"]?.jsonPrimitive?.booleanOrNull != false) bits = bits or 0x20
        return bits
    }

    // ── FNV-1a 64. Chosen over MD5/SHA because this runs on every tree read: it is
    // allocation-free, needs no provider lookup, and collision risk is irrelevant
    // when a false "same screen" costs one stale-looking perceive, not correctness
    // (the generation counter and event activity still gate the dangerous paths).

    private const val FNV_OFFSET = -3750763034362895579L // 14695981039346656037 unsigned
    private const val FNV_PRIME = 1099511628211L

    private fun Long.mix(s: String): Long {
        var h = this
        for (c in s) {
            h = (h xor (c.code and 0xFF).toLong()) * FNV_PRIME
            h = (h xor (c.code shr 8).toLong()) * FNV_PRIME
        }
        return (h xor SEPARATOR) * FNV_PRIME
    }

    private fun Long.mix(v: Int): Long {
        var h = this
        var x = v
        repeat(4) {
            h = (h xor (x and 0xFF).toLong()) * FNV_PRIME
            x = x shr 8
        }
        return h
    }

    private fun Long.mix(v: Long): Long {
        var h = this
        var x = v
        repeat(8) {
            h = (h xor (x and 0xFF)) * FNV_PRIME
            x = x shr 8
        }
        return h
    }

    /** Field delimiter, so ("ab","c") and ("a","bc") cannot hash alike. */
    private const val SEPARATOR = 0x1FL

    private fun JsonObject.str(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.bool(name: String): Boolean =
        get(name)?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.int(name: String): Int? =
        get(name)?.jsonPrimitive?.intOrNull
}
