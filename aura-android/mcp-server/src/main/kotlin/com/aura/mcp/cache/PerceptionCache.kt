package com.aura.mcp.cache

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.cache.SomIds.Companion.identity
import com.aura.mcp.tools.ScreenSignature
import com.aura.mcp.tools.UiTreeToElements

/**
 * Caches the latest perceive_screen detection results, mapping som_id → the point a touch
 * reaches it at (its centre, or the centre of its visible part when something covers the
 * centre; see [com.aura.mcp.tools.Occlusion]).
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
    /** The som_id numbering every look that feeds this cache shares; see [SomIds]. */
    val somIds: SomIds = SomIds(),
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
        /**
         * som_id → som_id of what covers it completely ([UNNUMBERED_COVER] when the cover has
         * no number). Absent means reachable.
         */
        val coveredBy: Map<Int, Int>,
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
        /** The elements as numbered, and their app: what `read_screen changes_only` compares against. */
        val elements: List<DetectedElement>,
        val pkg: String?,
    )

    @Volatile
    private var capture: Capture = Capture(
        coordinates = emptyMap(),
        coveredBy = emptyMap(),
        hasLabel = emptyMap(),
        labels = emptyMap(),
        bounds = emptyMap(),
        generation = Long.MIN_VALUE,
        activity = Long.MIN_VALUE,
        signature = null,
        fingerprints = emptyMap(),
        elements = emptyList(),
        pkg = null,
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
        pkg: String? = null,
        /** False for a vision look: `read_screen changes_only` keeps diffing against the last tree look. */
        baseline: Boolean = true,
    ) {
        val prev = capture
        lastWasVision = !baseline
        // Map each cover's bounds back to a som_id on THIS numbering; covers are recorded as
        // bounds because som_ids are reassigned after occlusion runs.
        // associateBy keeps the last duplicate, so interactive elements sort last to win.
        val byBox = elements.sortedBy { it.interactive }.associateBy { it.bbox }
        capture = Capture(
            coordinates = elements.associate { element ->
                element.somId to (element.tapPoint ?: (element.bbox.centerX to element.bbox.centerY))
            },
            coveredBy = elements.mapNotNull { e ->
                e.coveredBy?.let { cover -> e.somId to (byBox[cover]?.somId ?: UNNUMBERED_COVER) }
            }.toMap(),
            hasLabel = elements.associate { it.somId to it.label.isNotBlank() },
            labels = elements.filter { it.label.isNotBlank() }.associate { it.somId to it.label },
            bounds = elements.associate { it.somId to it.bbox },
            generation = generation(),
            activity = activityAtCapture ?: activity(),
            signature = signature,
            fingerprints = elements.associate { it.somId to it.fingerprint() },
            elements = if (baseline) elements else prev.elements,
            pkg = if (baseline) pkg else prev.pkg,
        )
    }

    /**
     * True when the last look was `perceive_screen`. Its result replaced the tree look in the
     * model's context, so a changes-only read must send the whole screen, not a diff against a
     * look the model can no longer see.
     */
    @Volatile
    var lastWasVision: Boolean = false
        private set

    /** The last look's elements and app, with NO staleness check: for diffing, never for touching. */
    fun previous(): Pair<List<DetectedElement>, String?> = capture.let { it.elements to it.pkg }

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

        // A vision box has no tree node, and its label is OCR text that could match a tree
        // element with the same words: never rescue it by the tree.
        if (somId > VISION_ID_BASE) return stale(generationMoved)

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
            val same = liveElements.firstOrNull { it.fingerprint() == mine }
            if (same != null) {
                // The element itself is untouched, but something may now sit on it — a sheet
                // or dialog that opened after the look. Its own fingerprint cannot see that.
                if (same.coveredBy != null) {
                    return Resolution.Stale(
                        "something now covers that element (a sheet, dialog or popup opened " +
                            "after the last look), so a tap there would land on it instead",
                    )
                }
                val at = same.tapPoint ?: (same.bbox.centerX to same.bbox.centerY)
                return Resolution.Fresh(at.first, at.second)
            }
            moved(snap, somId, liveElements, live, captured)?.let { return it }
        }

        // Either the screen genuinely differs, or we could not tell (no probe, or a
        // tree-blind surface where the hash means nothing). Fail closed — a wrong tap
        // is far worse than a redundant perceive.
        return stale(generationMoved)
    }

    private fun stale(generationMoved: Boolean): Resolution =
        Resolution.Stale(
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

    /**
     * Third rescue: the element slid (a list scrolled, a card was inserted above it, a chat
     * re-sorted), so its bounds differ but it is still on this screen. Follow it by
     * [SomIds.identity] and tap where it is now.
     *
     * - Contract: Fresh only when the app is the same, at least half the captured identities
     *   are still on screen (the same screen, not a new one with a same-named "OK"), the label
     *   is not blank, and exactly one element carries that identity on each side. Null otherwise,
     *   and the caller fails closed.
     */
    private fun moved(
        snap: Capture,
        somId: Int,
        liveElements: List<DetectedElement>,
        live: ScreenSignature.Signature?,
        captured: ScreenSignature.Signature?,
    ): Resolution? {
        if (live == null || captured == null || live.treeBlind || live.packageName != captured.packageName) return null
        val mine = snap.elements.firstOrNull { it.somId == somId }?.takeIf { it.label.isNotBlank() } ?: return null
        val key = mine.identity()
        if (snap.elements.count { it.identity() == key } != 1) return null
        val then = snap.elements.mapTo(HashSet()) { it.identity() }
        val now = liveElements.mapTo(HashSet()) { it.identity() }
        // Same rule as SomIds.assign: a small sheet's labels inside a big new screen is not the same screen.
        if (then.count { it in now } * 2 < maxOf(then.size, now.size)) return null
        val match = liveElements.singleOrNull { it.identity() == key } ?: return null
        if (match.coveredBy != null) {
            return Resolution.Stale("something now covers that element (a sheet, dialog or popup opened after the last look)")
        }
        val at = match.tapPoint ?: (match.bbox.centerX to match.bbox.centerY)
        return Resolution.Fresh(at.first, at.second)
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
     * The som_ids at the last look whose label is [label] (case and spacing aside) and that
     * nothing covers; interactive ones only, when any match. No staleness check: the caller acts
     * through `tap`, which still refuses a stale id.
     * - Contract: a host's `do_steps` stops unless this returns exactly one id.
     */
    fun idsFor(label: String): List<Int> {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        val want = norm(label).takeIf { it.isNotEmpty() } ?: return emptyList()
        val c = capture
        val hits = c.labels.filter { (id, l) -> norm(l) == want && c.coveredBy[id] == null }.keys
        val interactive = c.elements.filter { it.somId in hits && it.interactive }.map { it.somId }
        return interactive.ifEmpty { hits.toList() }.sorted()
    }

    /**
     * [somId]'s bounds from the last look, with NO staleness check — call [resolve] first and
     * only use this once it said Fresh. Null when the som_id is not cached.
     */
    fun boundsFor(somId: Int): BBox? = capture.bounds[somId]

    /**
     * The som_id of what covers [somId] completely at the last look, [UNNUMBERED_COVER] when the
     * cover has no number, or null when [somId] is reachable. No staleness check.
     */
    fun coveredBy(somId: Int): Int? = capture.coveredBy[somId]

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
            coveredBy = emptyMap(),
            hasLabel = emptyMap(),
            labels = emptyMap(),
            bounds = emptyMap(),
            generation = Long.MIN_VALUE,
            activity = Long.MIN_VALUE,
            signature = null,
            fingerprints = emptyMap(),
            elements = emptyList(),
            pkg = null,
        )
    }

    companion object {
        /** [coveredBy] value for a cover that has no som_id on the current numbering. */
        const val UNNUMBERED_COVER = -1

        /** `perceive_screen` numbers its vision boxes above this, so they never share an id with the tree. */
        const val VISION_ID_BASE = 1000

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
        internal fun DetectedElement.fingerprint(): Long {
            val q = ScreenSignature.BOUNDS_QUANTUM
            return listOf(
                bbox.x1 / q, bbox.y1 / q, bbox.x2 / q, bbox.y2 / q,
                elementType, label.replace(DIGITS, "#"),
                interactive, editable, scrollable, longClickable, wrapper, enabled, checked,
            ).hashCode().toLong()
        }
    }
}
