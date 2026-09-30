package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [mergeElements]: ui_tree bounds win on overlap (exact, from the accessibility
 * system) — but a blank tree label must borrow the overlapping CV element's
 * label instead of the CV finding being silently dropped. See
 * [PerceiveScreenTool]'s doc comment on `mergeElements` for the Wi-Fi toggle
 * row motivating case.
 */
class MergeElementsTest {

    private fun el(somId: Int, x1: Int, y1: Int, x2: Int, y2: Int, label: String, type: String = "element") =
        DetectedElement(somId = somId, bbox = BBox(x1, y1, x2, y2), elementType = type, label = label, confidence = 1f)

    /**
     * CROSS-MODULE INVARIANT — every ui_tree element comes FIRST, contiguously.
     *
     * The wire payload dropped the per-element `source` field, so `ui_tree_count`
     * plus this ordering is the ONLY way a consumer can tell an accessibility fact
     * from a vision guess. `ActionGuard.stableTreeDigest` (in `:app`) relies on it
     * directly: it takes `e.take(ui_tree_count)` as the deterministic half of the
     * screen and hashes that for loop detection.
     *
     * Pinned here rather than left implicit because breaking it fails SILENTLY and
     * across a module boundary: interleave or reorder this list and the guard
     * starts digesting jittery YOLO boxes as stable geometry, the signature stops
     * matching between identical screens, and the agent loops unchecked — with
     * every test still green. That is exactly how the shape mismatch this test was
     * written alongside went unnoticed. Same class of hazard as the
     * `som_id == index + 1` coupling pinned in [PerceiveElementJsonTest].
     */
    @Test
    fun `all ui_tree elements precede every omniparser element`() {
        val tree = listOf(
            el(1, 0, 0, 100, 100, label = "Back"),
            el(2, 0, 200, 100, 300, label = "Home"),
        )
        val cv = listOf(
            el(1, 500, 500, 600, 600, label = "icon"),
            el(2, 700, 700, 800, 800, label = "banner"),
        )
        val merged = mergeElements(tree, cv, uiTreeUsable = true)

        val treeCount = merged.count { it.source == "ui_tree" }
        assertEquals(2, treeCount)
        assertEquals(
            treeCount,
            merged.takeWhile { it.source == "ui_tree" }.size,
            "ui_tree elements must be contiguous and first — ui_tree_count is meaningless otherwise",
        )
    }

    @Test
    fun `CV element with no tree overlap is kept as its own box`() {
        val tree = listOf(el(1, 0, 0, 100, 100, label = "Back"))
        val cv = listOf(el(1, 500, 500, 600, 600, label = "icon"))
        val merged = mergeElements(tree, cv, uiTreeUsable = true)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.source == "omniparser" && it.element.label == "icon" })
    }

    @Test
    fun `CV element overlapping an already-labeled tree box is dropped, tree label unchanged`() {
        val tree = listOf(el(1, 0, 0, 100, 100, label = "Refresh"))
        val cv = listOf(el(1, 10, 10, 90, 90, label = "refresh_icon"))
        val merged = mergeElements(tree, cv, uiTreeUsable = true)
        assertEquals(1, merged.size, "the CV box must not survive as a second, duplicate box")
        assertEquals("Refresh", merged.single().element.label, "tree's own good label must win, not be overwritten")
        assertEquals("ui_tree", merged.single().source)
    }

    @Test
    fun `CV element overlapping a BLANK tree box splices its label onto the tree box`() {
        // Reproduces the Wi-Fi toggle row: the clickable tree box has no label
        // of its own; CV correctly detects the switch and has a usable label.
        val tree = listOf(el(1, 0, 363, 1240, 538, label = ""))
        val cv = listOf(el(1, 995, 412, 1128, 496, label = "toggle_switch"))
        val merged = mergeElements(tree, cv, uiTreeUsable = true)
        assertEquals(1, merged.size, "still one box, not two — CV donates its label, doesn't add a duplicate")
        assertEquals("toggle_switch", merged.single().element.label)
        assertEquals("ui_tree", merged.single().source, "bounds/source stay the tree's — only the label is borrowed")
        // Tree's own (exact) bounds are preserved, NOT replaced by CV's estimate.
        assertEquals(0, merged.single().element.bbox.x1)
        assertEquals(1240, merged.single().element.bbox.x2)
    }

    @Test
    fun `both tree and CV boxes blank stays blank, no crash, no fabricated label`() {
        val tree = listOf(el(1, 0, 0, 100, 100, label = ""))
        val cv = listOf(el(1, 10, 10, 90, 90, label = ""))
        val merged = mergeElements(tree, cv, uiTreeUsable = true)
        assertEquals(1, merged.size)
        assertEquals("", merged.single().element.label)
    }

    @Test
    fun `uiTreeUsable false never drops or splices a CV element`() {
        val tree = listOf(el(1, 0, 0, 100, 100, label = ""))
        val cv = listOf(el(1, 10, 10, 90, 90, label = "icon"))
        val merged = mergeElements(tree, cv, uiTreeUsable = false)
        assertEquals(2, merged.size, "when the tree is marked unusable, CV boxes must stand on their own")
        assertEquals("", merged.first { it.source == "ui_tree" }.element.label)
        assertEquals("icon", merged.first { it.source == "omniparser" }.element.label)
    }

    @Test
    fun `empty tree - all CV elements pass through untouched`() {
        val cv = listOf(el(1, 0, 0, 50, 50, label = "a"), el(2, 100, 100, 150, 150, label = "b"))
        val merged = mergeElements(emptyList(), cv, uiTreeUsable = true)
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.source == "omniparser" })
    }

    @Test
    fun `small CV box nested in a much bigger blank tree box still splices its label`() {
        // IOU here is ~0.05 (union dominated by the huge tree box) — far below
        // IOU_OVERLAP_DROP — yet the CV box is 100% contained. Must still merge
        // via the containment path, not just the IOU path.
        val tree = listOf(el(1, 0, 0, 1000, 200, label = ""))
        val cv = listOf(el(1, 400, 50, 500, 150, label = "toggle_switch"))
        val merged = mergeElements(tree, cv, uiTreeUsable = true)
        assertEquals(1, merged.size, "low-IOU but fully-contained CV box must still be absorbed, not left as a duplicate")
        assertEquals("toggle_switch", merged.single().element.label)
    }

    @Test
    fun `CV box only lightly overlapping a blank tree box does not merge`() {
        // Neither IOU nor containment clears threshold — a genuinely separate,
        // barely-touching element must not be treated as belonging to the tree box.
        val tree = listOf(el(1, 0, 0, 100, 100, label = ""))
        val cv = listOf(el(1, 90, 90, 200, 200, label = "elsewhere"))
        val merged = mergeElements(tree, cv, uiTreeUsable = true)
        assertEquals(2, merged.size, "barely-touching boxes are distinct targets, not the same element")
        assertEquals("", merged.first { it.source == "ui_tree" }.element.label)
    }

    @Test
    fun `empty CV - tree elements pass through untouched`() {
        val tree = listOf(el(1, 0, 0, 100, 100, label = "Save"))
        val merged = mergeElements(tree, emptyList(), uiTreeUsable = true)
        assertEquals(1, merged.size)
        assertEquals("Save", merged.single().element.label)
    }

    // ── A scroll host must not swallow the CV pass ─────────────────────────────
    //
    // Measured on the real device (Apple Music Library, run 1785299631262, 10:03):
    // the tree exposed the content area as ONE clickable container spanning
    // y=331..2280 of a 2772px screen, and the six playlist tiles inside it had no
    // nodes of their own. Every CV detection of a tile scored containment = 1.0
    // against that container, matched, and was dropped — so the full tier returned
    // only the CV boxes that fell OUTSIDE it: the status bar and AURA's own overlay
    // pill. The agent's actual target was unreachable, and it oscillated between two
    // nav tabs for 16 calls. Escalating to the full tier is pointless if the payoff
    // is deleted one stage later.

    private val screenH = 2772

    /** The Library content area as the tree reported it: one host, no children. */
    private fun scrollHost() = el(1, 0, 331, 1240, 2280, label = "")

    @Test
    fun `a page-sized scroll host does not absorb the CV boxes inside it`() {
        val tile = el(1, 60, 530, 410, 880, label = "Favourite Songs")
        val merged = mergeElements(listOf(scrollHost()), listOf(tile), uiTreeUsable = true, screenHeightPx = screenH)

        assertEquals(2, merged.size, "the tile is a real target the tree missed — it must survive the merge")
        assertTrue(merged.any { it.source == "omniparser" && it.element.label == "Favourite Songs" })
    }

    @Test
    fun `a scroll host does not steal the label of what it merely encloses`() {
        // Splicing here would leave ONE box, named for a tile, whose center is
        // nowhere near that tile — a target that lies about where it goes.
        val tile = el(1, 60, 530, 410, 880, label = "Favourite Songs")
        val merged = mergeElements(listOf(scrollHost()), listOf(tile), uiTreeUsable = true, screenHeightPx = screenH)

        assertEquals("", merged.first { it.source == "ui_tree" }.element.label)
    }

    @Test
    fun `a row-sized tree box still absorbs its CV child — the Wi-Fi toggle case`() {
        // The motivating case for containment matching, which must keep working: a
        // clickable Settings ROW (7% of screen height) with an unlabeled switch in it.
        // Rows are targets; page-sized hosts are not. Height is what tells them apart.
        val row = el(1, 0, 1000, 1240, 1200, label = "")
        val switch = el(1, 1000, 1050, 1180, 1150, label = "toggle_switch")
        val merged = mergeElements(listOf(row), listOf(switch), uiTreeUsable = true, screenHeightPx = screenH)

        assertEquals(1, merged.size, "a row is a legitimate tap target and still owns what sits in it")
        assertEquals("toggle_switch", merged.single().element.label)
    }

    @Test
    fun `unknown screen height falls back to the old containment behaviour`() {
        // Without a screen reference there is no way to tell a host from a row, and
        // absorbing is the safer default — it is what shipped before.
        val tile = el(1, 60, 530, 410, 880, label = "Favourite Songs")
        val merged = mergeElements(listOf(scrollHost()), listOf(tile), uiTreeUsable = true, screenHeightPx = 0)

        assertEquals(1, merged.size)
    }

    // ── absorption ceiling: the Amazon advert regression ─────────────────────

    @Test
    fun `a half-screen tree box must NOT swallow CV detections inside it`() {
        // Measured 2026-08-06, Amazon home (1240x2772). The advert's wrapper Views are
        // 49-51% of screen height and carry chrome labels ("Sponsored Ad", "Leave
        // feedback on sponsored advertisement"). They cleared the old guard, which only
        // exempted boxes taller than 60%, and so absorbed EVERY CV detection below
        // y=131: the full tier ran, cost 683 ms, and its entire output was discarded
        // except the status bar. The visible Samsung offer became unreadable.
        val advertWrapper = el(1, 56, 798, 903, 2156, label = "Leave feedback on sponsored advertisement")
        val offerText = el(1, 100, 900, 700, 1000, label = "Great Freedom Sale")

        val merged = mergeElements(
            uiTreeElements = listOf(advertWrapper),
            cvElements = listOf(offerText),
            uiTreeUsable = true,
            screenHeightPx = 2772,
        )

        assertEquals(2, merged.size, "the CV finding must survive as its own box")
        assertTrue(merged.any { it.source == "omniparser" && it.element.label == "Great Freedom Sale" })
    }

    @Test
    fun `a settings row still swallows the switch inside it`() {
        // The shape absorption exists for, and which must keep working: a row is a few
        // percent of screen height, and CV finding the switch inside it is a duplicate
        // of the row, not a separate target.
        val row = el(1, 0, 363, 1240, 538, label = "")
        val switch = el(1, 995, 412, 1128, 496, label = "toggle_switch")

        val merged = mergeElements(listOf(row), listOf(switch), uiTreeUsable = true, screenHeightPx = 2772)

        assertEquals(1, merged.size, "the switch is the row, not a second target")
        assertEquals("toggle_switch", merged.single().element.label, "its label is spliced onto the row")
    }

    @Test
    fun `absorption ceiling and container ceiling are different questions`() {
        // 0.25 (may this absorb) is deliberately NOT the same number as
        // TreeSufficiency.CONTAINER_HEIGHT_FRACTION 0.6 (is this a page host). Moving
        // the latter to catch these adverts would re-break the coverage heuristic it
        // was introduced to protect.
        val screen = 2772
        assertTrue(mayAbsorbByContainment(BBox(0, 0, 1240, (screen * 0.07).toInt()), screen), "a row absorbs")
        assertTrue(!mayAbsorbByContainment(BBox(0, 0, 1240, (screen * 0.49).toInt()), screen), "half a screen does not")
        assertTrue(!mayAbsorbByContainment(BBox(0, 0, 1240, (screen * 0.90).toInt()), screen), "a page host does not")
    }

    @Test
    fun `unknown screen height keeps the previous absorb behaviour`() {
        // With no reference we cannot tell a row from a host; absorbing is what callers
        // without geometry did before, so behaviour must not silently change for them.
        assertTrue(mayAbsorbByContainment(BBox(0, 0, 1240, 2000), 0))
    }

    @Test
    fun `the IOU arm still absorbs a CV box the same size as the wrapper`() {
        // The absorption ceiling guards only the CONTAINMENT arm; IOU is left unguarded
        // on purpose because it is size-sensitive. Pinning the boundary here so nobody
        // "fixes" P1 harder by guarding IOU too:
        //   small text inside the 49% wrapper  -> IOU 0.05  -> survives (the P1 fix)
        //   a CV box that IS the whole advert  -> IOU 0.99  -> absorbed (same target)
        // Absorbing the second is correct: two boxes of the same size in the same place
        // describe one thing, and shipping both would be a duplicate, not a rescue.
        val wrapper = el(1, 56, 798, 903, 2156, label = "Sponsored Ad")

        val smallInside = mergeElements(
            listOf(wrapper), listOf(el(1, 100, 900, 700, 1000, label = "Great Freedom Sale")),
            uiTreeUsable = true, screenHeightPx = 2772,
        )
        assertEquals(2, smallInside.size, "small text inside a half-screen box must survive")

        val sameSize = mergeElements(
            listOf(wrapper), listOf(el(1, 60, 800, 900, 2150, label = "advert")),
            uiTreeUsable = true, screenHeightPx = 2772,
        )
        assertEquals(1, sameSize.size, "a CV box congruent with the wrapper is the same target")
    }

    // ── som_id <-> array index ───────────────────────────────────────────────

    @Test
    fun `renumber makes som_id equal the array index plus one`() {
        // The wire format DROPS som_id and relies on position, so this coupling failing
        // is silent: no error, just a tap on the wrong element. Pinned over the real
        // merge+renumber path, not over a hand-built list.
        val tree = List(4) { el(99, it * 10, 0, it * 10 + 9, 50, label = "t$it") }
        val cv = listOf(el(99, 500, 500, 560, 560, label = "cv"))

        val out = renumberAndFlagWrappers(
            mergeElements(tree, cv, uiTreeUsable = true, screenHeightPx = 2772),
            screenHeightPx = 2772,
        )

        assertEquals(5, out.size)
        out.forEachIndexed { i, m ->
            assertEquals(i + 1, m.element.somId, "element at index $i must be som_id ${i + 1}")
        }
    }

    @Test
    fun `renumber flags boxes too big to aim at, using the absorption ceiling`() {
        // Same number as absorption on purpose: "bigger than a row" is what makes a box
        // both unfit to swallow a CV detection and unfit to be a tap target. The 49%
        // advert wrapper must come back flagged, or it survives absorption only to be
        // offered to the model as an ordinary blue button covering a third of the screen.
        val row = el(1, 0, 363, 1240, 538, label = "Wi-Fi")
        val advert = el(2, 56, 798, 903, 2156, label = "Sponsored Ad")

        val out = renumberAndFlagWrappers(
            listOf(MergedElement(row, "ui_tree"), MergedElement(advert, "ui_tree")),
            screenHeightPx = 2772,
        )

        assertTrue(!out[0].element.wrapper, "a settings row is a real target")
        assertTrue(out[1].element.wrapper, "a half-screen advert wrapper is not")
    }
}
