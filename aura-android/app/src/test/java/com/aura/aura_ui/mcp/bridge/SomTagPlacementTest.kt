package com.aura.aura_ui.mcp.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-geometry tests for SoM number-tag placement ([placeSomTags]). No Canvas,
 * no Android — plain JVM (`./gradlew app:test`).
 *
 * These lock in the declutter fix: number tags on dense screens must not stack
 * on the same pixel, must stay anchored to their own box, and must never run
 * off the image edges.
 */
class SomTagPlacementTest {

    private fun tag(l: Float, t: Float, r: Float, b: Float, w: Float = 30f, h: Float = 30f) =
        TagRequest(FRect(l, t, r, b), tagWidth = w, tagHeight = h)

    @Test
    fun `single tag takes the default outside-top-left corner`() {
        val out = placeSomTags(listOf(tag(100f, 100f, 200f, 160f)), imageW = 1000f, imageH = 1000f)
        assertEquals(1, out.size)
        // Default anchor: box.left, box.top - tagHeight.
        assertEquals(100f, out[0].left, 0.01f)
        assertEquals(70f, out[0].top, 0.01f)
    }

    @Test
    fun `two boxes sharing a top-left do not stack their tags`() {
        // Same origin → default corner would collide; the second must relocate.
        val a = tag(100f, 100f, 400f, 400f)
        val b = tag(100f, 100f, 380f, 380f)
        val out = placeSomTags(listOf(a, b), imageW = 1000f, imageH = 1000f)
        assertFalse("adjacent tags must not overlap", out[0].intersects(out[1]))
    }

    @Test
    fun `every placed tag stays within its own box horizontal span`() {
        // A tag anchored to a corner of box i must sit within [box.left, box.right]
        // — it never drifts toward a neighbour.
        val boxes = listOf(
            tag(50f, 50f, 250f, 250f),
            tag(60f, 55f, 240f, 240f),
            tag(70f, 60f, 230f, 230f),
        )
        val out = placeSomTags(boxes, imageW = 1000f, imageH = 1000f)
        for (i in boxes.indices) {
            val box = boxes[i].box
            val r = out[i]
            assertTrue("tag $i left within box", r.left >= box.left - 0.01f)
            assertTrue("tag $i right within box", r.right <= box.right + 0.01f)
        }
    }

    @Test
    fun `tag on a box at the top edge is clamped into the image`() {
        // box.top - tagHeight would be negative; must clamp to 0.
        val out = placeSomTags(listOf(tag(10f, 5f, 200f, 120f)), imageW = 1000f, imageH = 1000f)
        assertTrue("top clamped to image", out[0].top >= 0f)
    }

    @Test
    fun `tag on a box at the right edge stays inside the image`() {
        val imageW = 500f
        val out = placeSomTags(listOf(tag(480f, 100f, 500f, 200f, w = 40f)), imageW = imageW, imageH = 1000f)
        assertTrue("right edge inside image", out[0].right <= imageW + 0.01f)
        assertTrue("left non-negative", out[0].left >= 0f)
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(placeSomTags(emptyList(), 100f, 100f).isEmpty())
    }

    /**
     * Centre anchors earn their keep: a row of same-height siblings sharing a top
     * edge used to have only the two top corners each, so the third onwards piled
     * up. With mid-edge anchors a small row places cleanly.
     */
    @Test
    fun `a row of identical siblings all place without overlapping`() {
        val row = (0 until 4).map { i ->
            val left = 100f + i * 210f
            tag(left, 300f, left + 200f, 400f)
        }
        val out = placeSomTags(row, imageW = 1200f, imageH = 1000f)
        for (i in out.indices) {
            for (j in i + 1 until out.size) {
                assertFalse("tags $i and $j must not overlap", out[i].intersects(out[j]))
            }
        }
    }

    /**
     * When no anchor is clean, the loser must take the one that overlaps LEAST —
     * not the default corner, which is where everything already piles up.
     *
     * Built so the two strategies visibly disagree. A first tag with an oversized
     * label (70x100) lands at x∈[60,130] and blankets the LEFT half of the second
     * box's anchor set, so every remaining anchor collides but by different amounts:
     * the default overlaps 900px², top-centre 600px², top-right only 300px².
     * First-candidate would return the 900; least-overlap must return the 300.
     *
     * Note a tag exactly the size of its box collapses all ten anchors onto two
     * points and makes every overlap tie — which is why the box here is wider than
     * its tag.
     */
    @Test
    fun `hopeless cluster picks the least-overlapping anchor not the default`() {
        // Placed first (smallest box). Its default anchor puts the big label at (60,60)-(130,160).
        val blanket = tag(60f, 160f, 80f, 170f, w = 70f, h = 100f)
        // Anchors span x∈[100,160]; only the right-hand ones escape the blanket.
        val crowded = tag(100f, 100f, 160f, 130f, w = 40f, h = 30f)

        val out = placeSomTags(listOf(blanket, crowded), imageW = 1000f, imageH = 1000f)

        assertEquals(FRect(60f, 60f, 130f, 160f), out[0])
        assertEquals(
            "must take outside-top-RIGHT (300px² overlap), not the default corner (900px²)",
            FRect(120f, 70f, 160f, 100f),
            out[1],
        )
    }

    /**
     * The absolute rule: crowding is resolved by moving a tag around its OWN box,
     * never below it into the next row's territory. A number over the wrong control
     * is a wrong tap, which is worse than two numbers touching.
     */
    @Test
    fun `no tag is ever placed below its own box`() {
        val boxes = List(6) { i -> tag(100f, 100f + i * 20f, 300f, 200f + i * 20f) }
        val out = placeSomTags(boxes, imageW = 1000f, imageH = 1000f)
        for (i in boxes.indices) {
            assertTrue(
                "tag $i must not sit below its box bottom",
                out[i].bottom <= boxes[i].box.bottom + 0.01f,
            )
        }
    }
}
