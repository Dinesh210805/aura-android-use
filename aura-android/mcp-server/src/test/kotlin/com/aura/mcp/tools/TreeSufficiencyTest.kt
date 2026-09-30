package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tiered perception gate: when the accessibility tree alone is rich enough to
 * target the screen, `perceive_screen` skips the CV pass (YOLO + OCR — the
 * 0.4–7 s tail) entirely. The gate must stay conservative: a sparse or mostly
 * unlabeled tree (games, WebView, Canvas, Flutter blobs) escalates to full CV,
 * because a wrongly-marked tree confuses the model worse than the latency costs.
 */
class TreeSufficiencyTest {

    private fun element(id: Int, label: String) = DetectedElement(
        somId = id,
        bbox = BBox(x1 = 0, y1 = id * 100, x2 = 200, y2 = id * 100 + 80),
        elementType = "clickable",
        label = label,
        confidence = 1.0f,
    )

    private fun tree(count: Int, labeledCount: Int): List<DetectedElement> =
        (1..count).map { element(it, if (it <= labeledCount) "Label $it" else "") }

    @Test
    fun `a rich well-labeled tree is sufficient`() {
        // WhatsApp home style: dozens of interactive nodes, mostly labeled.
        assertTrue(TreeSufficiency.sufficient(tree(count = 24, labeledCount = 20)))
    }

    @Test
    fun `an empty tree is never sufficient`() {
        assertFalse(TreeSufficiency.sufficient(emptyList()))
    }

    @Test
    fun `a sparse tree escalates to CV`() {
        // Camera / game / dialog-only screens: a handful of nodes → the tree may
        // well be blind to the real controls; run OmniParser.
        assertFalse(TreeSufficiency.sufficient(tree(count = TreeSufficiency.MIN_ELEMENTS - 1, labeledCount = TreeSufficiency.MIN_ELEMENTS - 1)))
    }

    @Test
    fun `a mostly unlabeled tree escalates to CV`() {
        // Flutter/WebView symptom: many clickable blobs, few real labels — the
        // model cannot target these; OCR/CV must fill in.
        assertFalse(TreeSufficiency.sufficient(tree(count = 20, labeledCount = 2)))
    }

    @Test
    fun `the boundary values pass`() {
        val minLabeled = kotlin.math.ceil(TreeSufficiency.MIN_ELEMENTS * TreeSufficiency.MIN_LABELED_FRACTION).toInt()
        assertTrue(TreeSufficiency.sufficient(tree(count = TreeSufficiency.MIN_ELEMENTS, labeledCount = minLabeled)))
    }

    @Test
    fun `stacked overlapping strips escalate to CV — the Apple Music pathology`() {
        // Observed on the real device: Apple Music's tree reported the playlist
        // grid as ~8 full-width strips stacked on top of each other (pairwise
        // IoU ≫ 0.5) while the real tiles/rows had no boxes at all. Count and
        // label checks passed; the structure is the tell.
        val strips = (1..8).map { i ->
            DetectedElement(
                somId = i,
                // Full-width bands offset by 40px each, 200px tall → heavy mutual overlap.
                bbox = BBox(x1 = 0, y1 = 300 + i * 40, x2 = 1080, y2 = 500 + i * 40),
                elementType = "clickable",
                label = "Playlist row $i",
                confidence = 1.0f,
            )
        }
        val sane = tree(count = 6, labeledCount = 6) // disjoint, labeled
        assertFalse(TreeSufficiency.sufficient(strips + sane))
    }

    @Test
    fun `legitimate container-child nesting does not trip the overlap check`() {
        // A clickable row containing a small clickable button is normal Android:
        // IoU(child, container) is small because the union is the container.
        val container = DetectedElement(1, BBox(0, 0, 1080, 200), "clickable", "Row", 1.0f)
        val child = DetectedElement(2, BBox(900, 50, 1050, 150), "clickable", "Button", 1.0f)
        val rest = (3..12).map { element(it, "Label $it") }
        assertTrue(TreeSufficiency.sufficient(listOf(container, child) + rest))
    }

    // ── looksDegraded — the "app hides its UI from a11y services" detector ──────
    //
    // Swiggy pathology (measured live): a passing sufficiency check (32 labeled,
    // low-overlap elements) whose boxes ALL sit in the bottom favourites strip
    // (y≈2295-2716 of a 2772px screen) while the search bar, tabs, and banners
    // filling the top 80% have NO boxes. The count/label/overlap gate can't see
    // this; a large empty vertical band is the tell.

    private fun clusteredTree(topY: Int, bottomY: Int, count: Int): List<DetectedElement> =
        (1..count).map { i ->
            val y = topY + ((bottomY - topY) * (i - 1) / count)
            DetectedElement(i, BBox(56, y, 410, y + 40), "clickable", "Item $i", 1.0f)
        }

    @Test
    fun `elements clustered in a thin band leaving a large empty region are degraded`() {
        // Swiggy home: 32 boxes crammed into the bottom ~15% of a 2772px screen.
        val swiggy = clusteredTree(topY = 2295, bottomY = 2716, count = 32)
        assertTrue(TreeSufficiency.looksDegraded(swiggy, screenHeightPx = 2772))
    }

    @Test
    fun `elements spread across the screen are not degraded`() {
        // Normal app: boxes distributed top to bottom, no big empty band.
        val spread = (1..20).map { i ->
            val y = i * 130 // 130..2600 across a 2772px screen
            DetectedElement(i, BBox(0, y, 1080, y + 90), "clickable", "Row $i", 1.0f)
        }
        assertFalse(TreeSufficiency.looksDegraded(spread, screenHeightPx = 2772))
    }

    @Test
    fun `readable text does not vouch for a band with no tappable in it`() {
        // The tempting wrong fix. On the screen this heuristic was measured
        // against, the search bar filling the empty band IS tappable — the app
        // just does not expose it as clickable. Counting its label as coverage
        // would suppress the CV pass exactly where it is needed.
        val bottomBar = clusteredTree(topY = 2295, bottomY = 2716, count = 32)
        assertTrue(TreeSufficiency.looksDegraded(bottomBar, screenHeightPx = 2772))
    }

    @Test
    fun `unknown screen height cannot judge degradation`() {
        val swiggy = clusteredTree(topY = 2295, bottomY = 2716, count = 32)
        assertFalse(TreeSufficiency.looksDegraded(swiggy, screenHeightPx = 0))
    }

    @Test
    fun `empty element list is not flagged degraded`() {
        // Handled upstream as insufficient; looksDegraded must not throw or claim degraded.
        assertFalse(TreeSufficiency.looksDegraded(emptyList(), screenHeightPx = 2772))
    }

    // ── Containers must not count as coverage ──────────────────────────────────
    //
    // Apple Music, measured from run 1785293377395 (2026-07-29 08:19): the app was
    // still loading (a spinner on black) and the tree offered 11 boxes — 5 bottom-nav
    // tabs, the mini player, an overflow menu, and ONE scroll container spanning
    // y=331..2317 of a 2772px screen. That container's interval bridged the entire
    // dead zone, so the largest empty band measured 4.8% and the screen was served the
    // cheap tier. The model then tapped a phantom box. A scroll host is not a target,
    // and must not be allowed to vouch for the emptiness it merely encloses.

    /** The chrome that survives on every Apple Music screen, loaded or not. */
    private fun appleMusicChrome(): List<DetectedElement> = buildList {
        add(DetectedElement(1, BBox(1000, 134, 1180, 329), "clickable", "More options", 1.0f))
        add(DetectedElement(2, BBox(0, 2317, 1240, 2500), "clickable", "Favourite Songs", 1.0f))
        listOf("Home", "New", "Radio", "Library", "Search").forEachIndexed { i, tab ->
            add(DetectedElement(3 + i, BBox(i * 248, 2560, (i + 1) * 248, 2740), "clickable", tab, 1.0f))
        }
    }

    @Test
    fun `a full-height scroll container does not vouch for the empty screen it encloses`() {
        val container = DetectedElement(11, BBox(0, 331, 1240, 2317), "clickable", "", 1.0f)
        val loading = appleMusicChrome() + container

        assertTrue(
            TreeSufficiency.looksDegraded(loading, screenHeightPx = 2772),
            "chrome plus an empty scroll host is a blind view, not a covered screen",
        )
    }

    @Test
    fun `the same chrome without the container was already degraded — the container hid it`() {
        // Pins the regression precisely: only the container's presence flipped the verdict.
        assertTrue(TreeSufficiency.looksDegraded(appleMusicChrome(), screenHeightPx = 2772))
    }

    @Test
    fun `a large but sub-container element still counts as coverage`() {
        // A hero banner / now-playing artwork is a real tap target, not a scroll host.
        // Discarding these would make every media screen read as degraded.
        val hero = DetectedElement(1, BBox(0, 200, 1240, 1600), "clickable", "Now playing", 1.0f)
        val rows = (2..9).map { i ->
            DetectedElement(i, BBox(0, 1600 + (i - 2) * 140, 1240, 1700 + (i - 2) * 140), "clickable", "Row $i", 1.0f)
        }
        assertFalse(
            TreeSufficiency.looksDegraded(listOf(hero) + rows, screenHeightPx = 2772),
            "a 50%-tall banner is content, not a container",
        )
    }

    @Test
    fun `a tree made only of containers is degraded`() {
        // A page root and a scroll host and nothing else: there is no target anywhere,
        // however much screen area they claim to cover.
        val shells = listOf(
            DetectedElement(1, BBox(0, 0, 1240, 2772), "clickable", "", 1.0f),
            DetectedElement(2, BBox(0, 300, 1240, 2600), "scrollable", "", 1.0f),
        )
        assertTrue(TreeSufficiency.looksDegraded(shells, screenHeightPx = 2772))
    }
}
