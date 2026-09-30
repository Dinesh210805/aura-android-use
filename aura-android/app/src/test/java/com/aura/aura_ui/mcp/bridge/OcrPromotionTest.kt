package com.aura.aura_ui.mcp.bridge

import com.aura.mcp.bridge.BBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for OCR labeling + promotion ([bestOcrLabelFor],
 * [promotableOcrLines]). No ML Kit, no Android — plain JVM (`./gradlew app:test`).
 *
 * Regression anchor: the chess.com "Play Online" dialog. YOLO icon_detect boxed
 * the dialog surface but not the text-only START / CANCEL buttons; OCR read both
 * but its lines were used only as labels, so neither button was tappable
 * (review finding P15).
 */
class OcrPromotionTest {

    // ── promotion ────────────────────────────────────────────────────────────

    @Test
    fun `text buttons inside a large container box are promoted`() {
        // One big YOLO box (the dialog), two small OCR lines inside it —
        // IoU vs the dialog is tiny, so both must be promoted.
        val dialog = BBox(24, 430, 442, 640)
        val cancel = OcrLine("CANCEL", BBox(220, 580, 310, 610))
        val start = OcrLine("START", BBox(330, 580, 410, 610))

        val out = promotableOcrLines(listOf(dialog), listOf(cancel, start))

        assertEquals(listOf("CANCEL", "START"), out.map { it.text })
    }

    @Test
    fun `line that labels a yolo box is not promoted`() {
        val button = BBox(100, 100, 200, 140)
        val label = OcrLine("Play", BBox(105, 105, 195, 135)) // IoU ≈ 0.79

        assertTrue(promotableOcrLines(listOf(button), listOf(label)).isEmpty())
    }

    @Test
    fun `line above threshold on some box is not promoted even when not its best label`() {
        // Box A's best label is "primary" (IoU ≈ 0.95); "secondary" still overlaps
        // A at IoU 0.5 ≥ threshold → it belongs to an icon, not to empty space.
        val boxA = BBox(0, 0, 100, 100)
        val primary = OcrLine("primary", BBox(0, 0, 95, 100))
        val secondary = OcrLine("secondary", BBox(0, 0, 100, 50))

        assertEquals("primary", bestOcrLabelFor(boxA, listOf(primary, secondary)))
        assertTrue(promotableOcrLines(listOf(boxA), listOf(primary, secondary)).isEmpty())
    }

    @Test
    fun `single characters are filtered as noise`() {
        val counter = OcrLine("0", BBox(300, 50, 320, 80))

        assertTrue(promotableOcrLines(emptyList(), listOf(counter)).isEmpty())
    }

    @Test
    fun `with no yolo boxes every multi-char line is promoted`() {
        val lines = listOf(
            OcrLine("Friends", BBox(20, 650, 120, 680)),
            OcrLine("Show All", BBox(340, 650, 440, 680)),
            OcrLine("x", BBox(0, 0, 10, 10)), // noise — filtered
        )

        val out = promotableOcrLines(emptyList(), lines)

        assertEquals(listOf("Friends", "Show All"), out.map { it.text })
    }

    @Test
    fun `promotion preserves exact ocr bounds`() {
        val bounds = BBox(330, 580, 410, 610)
        val out = promotableOcrLines(emptyList(), listOf(OcrLine("START", bounds)))

        assertEquals(bounds, out.single().bbox)
    }

    // ── labeling (behaviour unchanged by the refactor) ───────────────────────

    @Test
    fun `bestOcrLabelFor picks the highest-iou line`() {
        val box = BBox(0, 0, 100, 100)
        val weak = OcrLine("weak", BBox(0, 0, 100, 20)) // IoU 0.2
        val good = OcrLine("good", BBox(0, 0, 90, 100)) // IoU 0.9

        assertEquals("good", bestOcrLabelFor(box, listOf(weak, good)))
    }

    @Test
    fun `bestOcrLabelFor returns empty below the match threshold`() {
        val box = BBox(0, 0, 100, 100)
        val far = OcrLine("far", BBox(500, 500, 600, 600))

        assertEquals("", bestOcrLabelFor(box, listOf(far)))
    }

    @Test
    fun `bboxIou is zero for disjoint boxes and one for identical boxes`() {
        val a = BBox(0, 0, 10, 10)

        assertEquals(0f, bboxIou(a, BBox(20, 20, 30, 30)), 0f)
        assertEquals(1f, bboxIou(a, BBox(0, 0, 10, 10)), 1e-6f)
    }
}
