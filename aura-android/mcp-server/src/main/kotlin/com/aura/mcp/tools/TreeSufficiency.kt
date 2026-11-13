package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement

/**
 * Whether an accessibility tree that looks full is hiding most of the screen: the check behind
 * `read_screen`'s ESCALATE for apps that withhold their controls from accessibility.
 *
 * - Why: such a screen LOOKS densely numbered (Swiggy: 32 boxes crammed into the bottom 15%), so
 *   the model grabs a nearby number instead of noticing the gap. It cannot tell from the list.
 * - Bias: a false positive costs one vision look (~1.5 s); a false negative leaves the agent blind.
 */
internal object TreeSufficiency {

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
     * flows, or it merely moves its damage downstream. It is excluded from
     * [looksDegraded]'s coverage math.
     *
     * Returns false when [screenHeightPx] is unknown (≤0): with no screen reference a
     * host cannot be told from a row, and every caller's safe default is "ordinary
     * element".
     */
    fun isContainer(bbox: BBox, screenHeightPx: Int): Boolean =
        screenHeightPx > 0 &&
            (bbox.y2 - bbox.y1).toDouble() / screenHeightPx > CONTAINER_HEIGHT_FRACTION

    /**
     * True when the tree's interactive elements leave a large contiguous vertical
     * band of the screen empty — the signature of an app that hides its UI from
     * accessibility services. `read_screen` says ESCALATE on true.
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
}
