package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement

/**
 * Tiered perception — the CHEAP-tier gate.
 *
 * `perceive_screen` runs the full CV pass (YOLO + OCR, 0.4–7 s on-device) on
 * every call today, even on screens where the accessibility tree alone is
 * rich, labeled, and pixel-accurate (WhatsApp home, Settings, Clock — the
 * majority of real usage). When this gate passes, the tool serves tree-only
 * boxes and skips CV entirely.
 *
 * Bias is deliberately conservative because the two error directions cost
 * very differently: wrongly skipping CV confuses the model into a wasted turn
 * (and historically poisoned whole runs); wrongly running CV costs ~1.5 s.
 * When in doubt, run CV.
 *
 * **These checks are stateless on purpose, and each one catches a failure the
 * model cannot self-report.** The ordinary case — "my target has no box, or no
 * box of its own" — is the MODEL's call via `detail="full"`; it can see that
 * from the screenshot and no server-side heuristic beats looking. What survives
 * here is what looks fine from the picture:
 *
 *  - [OVERLAP_IOU] — sibling boxes on top of each other. A clean numbered box
 *    sits on the target and taps the WRONG one (Apple Music's playlist grid,
 *    reported as stacked full-width strips at pairwise IoU ≈ 0.7). Nothing
 *    looks missing, so the model taps confidently and is confidently wrong.
 *  - [looksDegraded] — boxes crammed into a fraction of the screen (Swiggy's
 *    32 boxes in the bottom 15%). The screen LOOKS densely numbered, so the
 *    model grabs a nearby number instead of noticing the gap.
 *
 * The one remaining runtime signal, [com.aura.mcp.cache.PerceptionEscalation],
 * is likewise something the model cannot know: that it is re-looking at the
 * screen it was just served cheap.
 *
 * Upstream `UiTreeToElements.extract` already returns empty for
 * validation-failed trees (WebView/Canvas `requires_vision` screens) and
 * filters degenerate bounds, so those arrive here as insufficient for free.
 */
internal object TreeSufficiency {

    /**
     * Minimum interactive elements. Games/camera/SurfaceView screens expose a
     * handful of nodes at most — the tree may well be blind to the real
     * controls there, so CV must run.
     */
    const val MIN_ELEMENTS = 8

    /**
     * Minimum fraction of elements carrying a real label (text or
     * contentDescription). Icon-only unlabeled trees (badly built apps,
     * Flutter blobs) give the model nothing to target by — only the visual
     * pass helps there.
     */
    const val MIN_LABELED_FRACTION = 0.4

    /**
     * Same-size mutual overlap (IoU ≥ this) between two elements is a structural
     * lie: two SIBLING tap targets cannot occupy the same pixels. Deliberately
     * high so legitimate container→child nesting (small IoU, union ≈ container)
     * never trips it. Observed live: Apple Music reports its playlist grid as
     * stacked full-width strips with pairwise IoU ≈ 0.7 whose centers tap the
     * WRONG tiles.
     */
    const val OVERLAP_IOU = 0.5

    /** Trees where more than this fraction of elements heavily overlap a sibling escalate to CV. */
    const val MAX_OVERLAPPING_FRACTION = 0.3

    /**
     * A contiguous vertical band larger than this fraction of the screen height
     * that contains NO interactive element marks a DEGRADED tree — the app is
     * almost certainly withholding its real UI from third-party accessibility
     * services (measured on Swiggy: 32 boxes crammed into the bottom ~15% while
     * the search bar / tabs / banners filling the top ~80% had none). 0.5 is
     * deliberately loose: a false positive only costs a vision pass (~1.5 s,
     * still correct), while a false negative leaves the agent blind.
     */
    const val MAX_EMPTY_BAND_FRACTION = 0.5

    /**
     * An element taller than this fraction of the screen is a scroll host or page
     * root, not something the model can usefully tap — and it must not be counted as
     * COVERAGE, because covering a region is exactly what a container does whether or
     * not anything inside it is reachable.
     *
     * Without this exclusion a single `RecyclerView` cancels the whole coverage
     * heuristic. Measured live (Apple Music, still loading): 5 nav tabs + a mini
     * player + an overflow menu + one container spanning y=331..2317 of 2772 px. The
     * real empty band was 71.7%; the container bridged it and [looksDegraded] saw
     * 4.8%. Every modern app has such a host, so the heuristic was near-dead in the
     * field while passing its unit tests.
     *
     * 0.6 leaves ordinary content alone — hero banners and now-playing artwork run to
     * roughly half a screen and stay counted.
     *
     * HEIGHT, not area, is the discriminator, and the choice matters: a Settings row
     * containing a switch is ~27× the switch's area, while the Apple Music content
     * host is only ~18× a playlist tile's. An area ratio would call the legitimate
     * row the bigger offender. What actually separates them is that a row is a few
     * percent of the screen tall and a page host is most of it.
     */
    const val CONTAINER_HEIGHT_FRACTION = 0.6

    /**
     * See [CONTAINER_HEIGHT_FRACTION] — a host for targets, not a target. One
     * definition, deliberately shared: a container must be recognised everywhere it
     * flows, or it merely moves its damage downstream. It is currently excluded from
     * [looksDegraded]'s coverage math and from absorbing CV detections in
     * `mergeElements`.
     *
     * Returns false when [screenHeightPx] is unknown (≤0): with no screen reference a
     * host cannot be told from a row, and every caller's safe default is "ordinary
     * element".
     */
    fun isContainer(bbox: BBox, screenHeightPx: Int): Boolean =
        screenHeightPx > 0 &&
            (bbox.y2 - bbox.y1).toDouble() / screenHeightPx > CONTAINER_HEIGHT_FRACTION

    fun sufficient(elements: List<DetectedElement>): Boolean {
        if (elements.size < MIN_ELEMENTS) return false
        val labeled = elements.count { it.label.isNotBlank() }
        if (labeled.toDouble() / elements.size < MIN_LABELED_FRACTION) return false
        return overlappingFraction(elements) <= MAX_OVERLAPPING_FRACTION
    }

    /**
     * True when the tree LOOKS structurally rich (passes [sufficient]) yet its
     * interactive elements are clustered such that a large contiguous vertical
     * band of the screen is empty — the signature of an app that hides its UI
     * from accessibility services. Callers force the full vision tier on true.
     *
     * Returns false when [screenHeightPx] is unknown (≤0) or the list is empty:
     * without a screen reference, coverage can't be judged, and an empty list is
     * already handled as insufficient upstream.
     *
     * ### Why readable text does NOT count as coverage
     *
     * Tempting, and wrong. The band is measured over INTERACTIVE elements only —
     * not because the tree cannot see text there (it usually can; see
     * [ScreenText]), but because this heuristic asks *"can the agent ACT in this
     * region?"*, and text proves nothing about that. On the screen this was
     * measured against, the search bar occupying the empty band is genuinely
     * tappable — the app simply does not expose it as clickable. Letting its
     * label vouch for the band would suppress the CV pass on exactly the screen
     * that needs it, converting a 1.5 s false positive into a blind agent.
     */
    fun looksDegraded(elements: List<DetectedElement>, screenHeightPx: Int): Boolean {
        if (screenHeightPx <= 0 || elements.isEmpty()) return false
        val targets = elements.filterNot { isContainer(it.bbox, screenHeightPx) }
        // Nothing but scroll hosts and page roots: the tree offered no target anywhere,
        // however much screen area it claimed to cover.
        if (targets.isEmpty()) return true
        return largestEmptyBandPx(targets.map { it.bbox }, screenHeightPx).toDouble() /
            screenHeightPx > MAX_EMPTY_BAND_FRACTION
    }

    /**
     * Largest contiguous vertical span within [0, screenHeightPx] covered by NO
     * element's [y1, y2] range. Merges element vertical intervals, then scans the
     * gaps (including the top gap before the first interval and the bottom gap
     * after the last).
     */
    private fun largestEmptyBandPx(boxes: List<BBox>, screenHeightPx: Int): Int {
        val intervals = boxes
            .map { it.y1.coerceAtLeast(0) to it.y2.coerceAtMost(screenHeightPx) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        if (intervals.isEmpty()) return screenHeightPx

        var largestGap = 0
        var cursor = 0 // top of screen
        for ((start, end) in intervals) {
            if (start > cursor) largestGap = maxOf(largestGap, start - cursor)
            cursor = maxOf(cursor, end)
        }
        if (screenHeightPx > cursor) largestGap = maxOf(largestGap, screenHeightPx - cursor)
        return largestGap
    }

    /** Fraction of elements whose IoU with ANY other element reaches [OVERLAP_IOU]. */
    private fun overlappingFraction(elements: List<DetectedElement>): Double {
        if (elements.size < 2) return 0.0
        var overlapping = 0
        for (i in elements.indices) {
            for (j in elements.indices) {
                if (i != j && iou(elements[i].bbox, elements[j].bbox) >= OVERLAP_IOU) {
                    overlapping++
                    break
                }
            }
        }
        return overlapping.toDouble() / elements.size
    }

    private fun iou(a: BBox, b: BBox): Double {
        val ix1 = maxOf(a.x1, b.x1)
        val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2)
        val iy2 = minOf(a.y2, b.y2)
        if (ix2 <= ix1 || iy2 <= iy1) return 0.0
        val inter = (ix2 - ix1).toDouble() * (iy2 - iy1)
        val areaA = (a.x2 - a.x1).toDouble() * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1).toDouble() * (b.y2 - b.y1)
        return inter / (areaA + areaB - inter)
    }
}
