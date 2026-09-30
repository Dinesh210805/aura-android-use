package com.aura.mcp.cache

import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.tools.ScreenSignature

/**
 * Memoizes the expensive CV stage (YOLOv8 + OCR, ~1.5 s) against a screen signature.
 *
 * The case this exists for: the model perceives, does not act, and perceives again —
 * because the first answer was not enough, or it re-read its own context. Previously
 * that path was guaranteed to be the most expensive one available AND guaranteed to
 * produce the same pixels' worth of information. Now the second call reuses the first
 * call's detections and the caller can tell the model plainly that nothing has moved,
 * which is more actionable than another 1.5 s of identical boxes.
 *
 * Keyed on the **content** hash, not the layout hash: identical content means
 * literally the same screen, so the detections must be identical too. Keying on layout
 * would reuse detections across a screen whose text changed, and OCR labels are part
 * of what CV returns.
 *
 * Single entry on purpose. The access pattern is "the screen I am looking at right
 * now", not a working set, and one slot removes any eviction policy to reason about.
 * [TTL_MS] bounds staleness for anything a signature cannot see (a video frame behind
 * a static tree, a surface the tree does not describe).
 */
internal class CvMemo(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * All the state in ONE immutable object behind ONE volatile reference. Separate
     * fields would let a concurrent `put` land between [get]'s key check and its read
     * of the elements, handing back detections belonging to a different screen —
     * exactly the mismatch this cache exists to prevent.
     */
    private class Snapshot(
        val key: Long,
        val packageName: String,
        val storedAtMs: Long,
        val hit: Hit,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    data class Hit(
        val elements: List<DetectedElement>,
        val sourceWidthPx: Int,
        val sourceHeightPx: Int,
        val engine: String?,
    )

    /** Detections for [signature], or null on miss, expiry, or a tree-blind screen. */
    fun get(signature: ScreenSignature.Signature?): Hit? {
        val sig = signature ?: return null
        // A blind tree's hash is constant while the screen moves freely — reusing
        // detections there would serve boxes for a screen that no longer exists.
        if (sig.treeBlind) return null
        val s = snapshot ?: return null
        if (s.key != sig.content || s.packageName != sig.packageName) return null
        if (clock() - s.storedAtMs > TTL_MS) return null
        return s.hit
    }

    fun put(
        signature: ScreenSignature.Signature?,
        elements: List<DetectedElement>,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        engine: String?,
    ) {
        val sig = signature ?: return
        if (sig.treeBlind) return
        snapshot = Snapshot(
            key = sig.content,
            packageName = sig.packageName,
            storedAtMs = clock(),
            hit = Hit(elements, sourceWidthPx, sourceHeightPx, engine),
        )
    }

    fun clear() {
        snapshot = null
    }

    companion object {
        /** Short enough that anything the signature cannot observe expires quickly. */
        const val TTL_MS = 5_000L
    }
}
