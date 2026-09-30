package com.aura.mcp.cache

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.tools.ScreenSignature
import com.aura.mcp.tools.UiTreeToElements

/**
 * Caches the latest perceive_screen detection results, mapping som_id → (center_x, center_y).
 *
 * This cache solves the coordinate-mismatch problem:
 * - perceive_screen returns elements at full-resolution device pixels (e.g. 1240×2772)
 * - But the image shown to the vision model is downscaled (~572×1280)
 * - If the model were to eyeball coordinates from the downscaled image, it would be wildly off
 * - Solution: Model picks an element by som_id from the SoM-annotated image (visual ground truth)
 *           We resolve som_id → (center_x, center_y) server-side from the full-resolution cache
 *           Gesture tools dispatch at FULL-RES pixels, never downscaled-image coordinates
 *
 * P1 (staleness): the cache also snapshots the [ScreenGeneration] counter at update time.
 * som_ids describe ONE captured screen; once any WRITE-scoped tool runs, those coordinates
 * point at a screen that may no longer exist, and
 * [resolve] reports [Resolution.Stale] instead of coordinates. Enforced server-side because
 * the server is the trust boundary for real gestures — external clients (WebRTC/proxy) have
 * no client-side ActionGuard.
 *
 * Thread-safe via simple @Volatile and atomic reads (no mutation of the map itself,
 * only replacement of the reference).
 */
internal class PerceptionCache(
    private val generation: () -> Long = { ScreenGeneration.current },
    private val activity: () -> Long = { ScreenActivity.meaningfulCount },
    /**
     * Re-reads the live UI tree, returning the raw snapshot payload (null when the
     * read failed). Supplied by the host (it needs the UI-tree bridge); null disables
     * both rescue paths below, which degrades this class to its previous counter-only
     * behaviour — safe, just more conservative.
     *
     * Hands back the payload rather than a [ScreenSignature.Signature] because the
     * second rescue needs the individual nodes, and re-reading the tree twice to get
     * them would double the cost of the one thing this path is supposed to make cheap.
     */
    private val screenProbe: (suspend () -> String?)? = null,
) {

    /** Outcome of resolving a som_id against the last perceived screen. */
    sealed interface Resolution {
        /** The perceive is current — safe to dispatch at these full-res pixels. */
        data class Fresh(val x: Int, val y: Int) : Resolution

        /** The som_id exists but describes a screen that has since changed. */
        data class Stale(val reason: String) : Resolution

        /** The som_id was never in the last perceive (or nothing was perceived yet). */
        data object Unknown : Resolution
    }

    /**
     * One perceive's worth of state, immutable, behind ONE volatile reference.
     *
     * These fields only mean anything TOGETHER: the coordinates, the screen they were
     * measured on, and the counters that decide whether that screen still exists. Held
     * as separate volatiles, a `resolve` running while a fresh perceive lands could read
     * the OLD coordinates and then the NEW signature, conclude "unchanged", and dispatch
     * a real tap at a dead position. Swapping one reference makes that impossible.
     */
    private class Capture(
        val coordinates: Map<Int, Pair<Int, Int>>,
        /** Diagnostic only — whether each som_id's element carried a non-blank label. */
        val hasLabel: Map<Int, Boolean>,
        /**
         * Each som_id's label text, for naming an element in a human sentence.
         *
         * Presentation only — nothing that dispatches input reads this. It exists so the
         * on-screen status strip can say "Tapping Playlists" instead of "tap som_id 12";
         * a wrong or missing label here costs a vaguer sentence and nothing else.
         */
        val labels: Map<Int, String>,
        /** Each som_id's full-resolution bounds, for gestures aimed INSIDE an element (scroll, swipe). */
        val bounds: Map<Int, BBox>,
        val generation: Long,
        /** Meaningful-event count at capture time — catches screens that moved on their own. */
        val activity: Long,
        /** Signature of the screen these som_ids describe. Null until a perceive supplies one. */
        val signature: ScreenSignature.Signature?,
        /**
         * Per-som_id identity hash — where the element sat and what it was, with no
         * reference to the rest of the screen. This is what lets a tap survive a screen
         * whose *other* pixels are moving; see [resolve].
         */
        val fingerprints: Map<Int, Long>,
    )

    @Volatile
    private var capture: Capture = Capture(
        coordinates = emptyMap(),
        hasLabel = emptyMap(),
        labels = emptyMap(),
        bounds = emptyMap(),
        generation = Long.MIN_VALUE,
        activity = Long.MIN_VALUE,
        signature = null,
        fingerprints = emptyMap(),
    )

    /**
     * Update the cache from the latest perceive_screen detection results.
     * Called immediately after renumbering elements in perceive_screen.
     * Re-snapshots the screen generation — a fresh perceive
     * makes the cache fresh by definition.
     *
     * @param elements renumbered elements with contiguous somId starting at 1
     */
    /**
     * @param activityAtCapture the [ScreenActivity] counter as of when [signature] and
     *   the element bounds were READ — not as of now. `perceive_screen` can spend ~1.5 s
     *   in the CV pass after reading the tree; sampling the counter here instead would
     *   swallow every event fired during that window, so a screen that moved mid-perceive
     *   would resolve Fresh and dispatch a tap at coordinates that no longer exist.
     *   Null falls back to now, which is right for callers that do no post-read work.
     */
    fun update(
        elements: List<DetectedElement>,
        signature: ScreenSignature.Signature? = null,
        activityAtCapture: Long? = null,
    ) {
        capture = Capture(
            coordinates = elements.associate { element ->
                element.somId to (element.bbox.centerX to element.bbox.centerY)
            },
            hasLabel = elements.associate { it.somId to it.label.isNotBlank() },
            labels = elements.filter { it.label.isNotBlank() }.associate { it.somId to it.label },
            bounds = elements.associate { it.somId to it.bbox },
            generation = generation(),
            activity = activityAtCapture ?: activity(),
            signature = signature,
            fingerprints = elements.associate { it.somId to it.fingerprint() },
        )
    }

    /** The screen these som_ids describe, for callers that need to compare against it. */
    fun signature(): ScreenSignature.Signature? = capture.signature

    /**
     * Resolve a som_id with staleness enforcement — the only resolution gesture tools
     * should use for dispatching real input. [Resolution.Stale] carries a
     * model-actionable reason (the fix is always: perceive_screen again).
     */
    suspend fun resolve(somId: Int): Resolution {
        // Read the capture ONCE: every check below must describe the same perceive.
        val snap = capture
        val coords = snap.coordinates[somId] ?: return Resolution.Unknown
        // No age limit: an old look at a screen nothing has touched is still right, and the
        // counters and probes below catch every way it could have moved (F1: a 30 s rule
        // refused correct taps on an idle Maps screen).

        // Fast path — nothing claims the screen moved. Costs no syscalls, and is the
        // overwhelmingly common case (perceive, then tap).
        val generationMoved = snap.generation != generation()
        val activityMoved = snap.activity != activity()
        if (!generationMoved && !activityMoved) return Resolution.Fresh(coords.first, coords.second)

        // Something says dirty — but both signals over-report in opposite ways, and
        // acting on either alone costs the model a whole perceive it may not need:
        //  - the generation counter bumps for WRITE tools that change no pixels at all
        //    (volume, mute, media transport, notification actions)
        //  - a meaningful event can fire for a banner or a toggle elsewhere while the
        //    screen these som_ids describe is untouched
        // So we pay ONE tree read (~50 ms) to ask the screen directly. Identical
        // signature means the coordinates still land where they were perceived, and we
        // save the far more expensive re-perceive. Ordering matters: this costs nothing
        // on the fast path above and only buys back the false alarms.
        val payload = runCatching { screenProbe?.invoke() }.getOrNull()
        val live = payload?.let { ScreenSignature.of(it) }
        val captured = snap.signature
        if (live != null && captured != null && live.sameContentAs(captured)) {
            return Resolution.Fresh(coords.first, coords.second)
        }

        // Second rescue: the whole screen differs, but does THIS element? A gesture asks
        // one question — "will my finger land on the thing the model picked" — and the
        // screen-wide hash answers a much bigger one. Anything that redraws continuously
        // (a playing video, a live view count, a spinner, a caret) changes that hash on
        // every read, so a feed screen could never resolve Fresh and the run deadlocked
        // into perceive → tap → STALE forever (observed on an Instagram reel, 2026-09-08).
        //
        // Matching the element's own fingerprint is narrow enough to be safe where
        // sameLayoutAs would not be: the bounds, class, label and flags all have to still
        // be there, so a list row rebinding new data under identical bounds still reads
        // Stale — which is the case that would otherwise tap the wrong item.
        //
        // Both sides go through UiTreeToElements so the fingerprints are comparable; a
        // som_id that came from the CV pass has no tree node and simply never matches,
        // which is the same fail-closed answer it gets today.
        val mine = snap.fingerprints[somId]
        if (payload != null && mine != null) {
            val liveElements = UiTreeToElements.extract(payload, minElements = 1)
            if (liveElements.any { it.fingerprint() == mine }) {
                return Resolution.Fresh(coords.first, coords.second)
            }
        }

        // Either the screen genuinely differs, or we could not tell (no probe, or a
        // tree-blind surface where the hash means nothing). Fail closed — a wrong tap
        // is far worse than a redundant perceive.
        return Resolution.Stale(
            if (generationMoved) {
                "the screen has changed since the last look (an action or " +
                    "navigation ran after it), so cached som_id coordinates are no longer valid"
            } else {
                // F20: most often this is the app still settling from the agent's own last
                // action (Maps reloading a route), not the screen moving by itself.
                "the screen changed since the last look (often still settling after your " +
                    "last action — a reload or animation — or a notification or background " +
                    "update), so cached som_id coordinates are no longer valid"
            },
        )
    }

    /**
     * Resolve a som_id to its (center_x, center_y) coordinates with NO staleness check.
     * Kept for diagnostics only — gesture dispatch must use [resolve].
     *
     * @param somId the SoM element number from the numbered boxes in perceive_screen
     * @return Pair(center_x, center_y) in full-resolution device pixels, or null if not cached
     */
    fun getCoordinates(somId: Int): Pair<Int, Int>? = capture.coordinates[somId]

    /**
     * Whether [somId]'s element carried a non-blank label at the last perceive —
     * diagnostic only, so a caller can notice a gesture about to act on a box
     * the tree/CV never named. Null when the som_id isn't cached (mirrors
     * [getCoordinates]'s no-staleness-check contract — this is a lookup, not
     * a gate).
     */
    fun hasLabel(somId: Int): Boolean? = capture.hasLabel[somId]

    /**
     * [somId]'s label text from the last perceive, or null when it had none.
     *
     * Deliberately does NOT enforce staleness the way [resolve] does. This names an element
     * in a sentence the user reads *after* the action; a slightly stale name is a slightly
     * wrong caption, whereas stale COORDINATES dispatch a tap into the wrong place. Never
     * use this to decide where to touch.
     */
    fun labelFor(somId: Int): String? = capture.labels[somId]

    /**
     * [somId]'s bounds from the last look, with NO staleness check — call [resolve] first and
     * only use this once it said Fresh. Null when the som_id is not cached.
     */
    fun boundsFor(somId: Int): BBox? = capture.bounds[somId]

    /**
     * Check if a som_id is currently known in the cache.
     */
    fun contains(somId: Int): Boolean = capture.coordinates.containsKey(somId)

    /**
     * Return the list of currently cached som_ids (for diagnostics/validation).
     */
    fun cachedSomIds(): List<Int> = capture.coordinates.keys.sorted()

    /**
     * Clear the cache (rarely needed, but available for testing or session reset).
     * Drops the signature and counters too — a half-cleared capture would let a
     * later resolve compare fresh counters against a dead screen.
     */
    fun clear() {
        capture = Capture(
            coordinates = emptyMap(),
            hasLabel = emptyMap(),
            labels = emptyMap(),
            bounds = emptyMap(),
            generation = Long.MIN_VALUE,
            activity = Long.MIN_VALUE,
            signature = null,
            fingerprints = emptyMap(),
        )
    }

    companion object {
        /** Digit runs in a label — see [fingerprint]. */
        private val DIGITS = Regex("\\d+")

        /**
         * One element's identity, independent of every other element on screen: where it
         * sits, what it is, what it says, what it can do. Deliberately excludes [somId]
         * (renumbered on every read) and `focused` (moves without moving the element).
         *
         * Bounds are quantized on [ScreenSignature.BOUNDS_QUANTUM], for the reason that
         * constant exists — a screen easing into place must not read as a different
         * element every animation frame. The cost is that a tap can land up to a quantum
         * off an element that drifted, which is the same trade the layout lane already makes.
         *
         * Digits in the label are blanked: a ticking ETA ("7 hours 13 minutes" → "14"), an
         * unread badge or a view count is the same element, and matching them refused every
         * tap on a live Maps screen (F1). A row that rebinds to a different NAME still differs.
         * Ceiling: rows told apart only by numbers (a call log of unsaved numbers) re-sorting
         * in the gap between look and tap would match.
         */
        private fun DetectedElement.fingerprint(): Long {
            val q = ScreenSignature.BOUNDS_QUANTUM
            return listOf(
                bbox.x1 / q, bbox.y1 / q, bbox.x2 / q, bbox.y2 / q,
                elementType, label.replace(DIGITS, "#"),
                interactive, editable, scrollable, longClickable, wrapper, enabled, checked,
            ).hashCode().toLong()
        }
    }
}
