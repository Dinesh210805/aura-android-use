package com.aura.aura_ui.mcp.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.aura.mcp.bridge.AnnotationGroup
import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.PerceptionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Adapter for [PerceptionBridge] that combines two on-device models:
 *
 *  - [OnnxYoloRunner]   — Microsoft OmniParser icon_detect, ~20MB ONNX
 *                         INT8. Produces pixel-accurate bboxes.
 *  - [MlKitTextReader]  — Google ML Kit Latin OCR, ~10MB bundled. Produces
 *                         text + bbox for any UI element with readable text.
 *
 * Merge rule: for each YOLO bbox, find the OCR line with the highest IoU.
 * If IoU ≥ 0.3 the OCR text becomes the element's label; otherwise the label
 * is the empty string and tools/callers fall back to "<elementType> at (cx, cy)"
 * style descriptors.
 *
 * **OCR promotion (review finding P15):** OCR lines that land on NO YOLO box
 * (max IoU < the label threshold) are promoted to elements of their own with
 * `elementType = "text"` instead of being discarded. icon_detect is trained on
 * icon-shaped interactables and is blind to text-only buttons ("START",
 * "CANCEL") by construction; upstream OmniParser pairs it with OCR regions as
 * first-class elements for exactly this reason. Single-character lines are
 * dropped as noise and the promoted set is capped (never silently — see log).
 *
 * **Coordinates always come from the CV model or OCR bounds, never from any
 * VLM.** This preserves the project-wide invariant.
 */
class AppPerceptionBridge(
    appContext: Context,
) : PerceptionBridge {

    private val yolo by lazy { OnnxYoloRunner(appContext) }
    private val ocr by lazy { MlKitTextReader() }

    /**
     * Build + calibrate the on-device perception model ahead of first use.
     * Call at service startup so the per-EP benchmark and graph compilation are
     * paid off the user-facing critical path. Idempotent; safe to call repeatedly.
     */
    suspend fun warmUp() {
        yolo.ensurePrepared()
    }

    override suspend fun detectElements(
        pngBytes: ByteArray,
        withOcr: Boolean,
        withAnnotatedImage: Boolean,
    ): PerceptionResult {
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        }.getOrNull()
            ?: return PerceptionResult(
                ok = false,
                elements = emptyList(),
                sourceWidthPx = 0,
                sourceHeightPx = 0,
                error = "Failed to decode PNG bytes",
            )

        // YOLO (blocking CPU/NPU) and OCR (ML Kit, suspend) are independent — run
        // them concurrently instead of back-to-back, and time each stage.
        return coroutineScope {
            val yoloDeferred = async(Dispatchers.Default) {
                val t0 = System.currentTimeMillis()
                val r = runCatching { yolo.detect(bitmap) }
                r to (System.currentTimeMillis() - t0)
            }
            val ocrDeferred = if (withOcr) {
                async(Dispatchers.Default) {
                    val t0 = System.currentTimeMillis()
                    val boxes = runCatching { ocr.readText(bitmap) }.getOrDefault(emptyList())
                    boxes to (System.currentTimeMillis() - t0)
                }
            } else {
                null
            }

            val (yoloResult, yoloMs) = yoloDeferred.await()
            val detect = yoloResult.getOrElse { t ->
                return@coroutineScope PerceptionResult(
                    ok = false,
                    elements = emptyList(),
                    sourceWidthPx = bitmap.width,
                    sourceHeightPx = bitmap.height,
                    error = "YOLO inference failed: ${t.message}",
                    timingsMs = mapOf("yolo" to yoloMs),
                    engine = yolo.activeProvider?.name,
                )
            }
            val rawDetections = detect.detections
            val yt = detect.timings
            val (ocrBoxes, ocrMs) = ocrDeferred?.await()
                ?: (emptyList<MlKitTextReader.TextBox>() to 0L)

            // Android Rect → pure BBox once; labeling + promotion below are
            // plain-JVM logic (unit-tested without Android).
            val ocrLines = ocrBoxes.map {
                OcrLine(it.text, BBox(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom))
            }

            val yoloElements = rawDetections.mapIndexed { idx, det ->
                val label = if (withOcr) bestOcrLabelFor(det.bbox, ocrLines) else ""
                DetectedElement(
                    somId = idx + 1,
                    bbox = det.bbox,
                    elementType = yolo.classNameOf(det.classId),
                    label = label,
                    confidence = det.confidence,
                )
            }

            // P15: OCR lines on no YOLO box become elements of their own —
            // text-only buttons are invisible to icon_detect by construction.
            val promotable = promotableOcrLines(rawDetections.map { it.bbox }, ocrLines)
            if (promotable.size > MAX_PROMOTED_OCR_ELEMENTS) {
                Log.w(
                    TAG,
                    "OCR promotion capped at $MAX_PROMOTED_OCR_ELEMENTS — " +
                        "dropped ${promotable.size - MAX_PROMOTED_OCR_ELEMENTS} text boxes " +
                        "(text-dense screen)",
                )
            }
            val promotedElements = promotable.take(MAX_PROMOTED_OCR_ELEMENTS).mapIndexed { i, line ->
                DetectedElement(
                    somId = yoloElements.size + i + 1,
                    bbox = line.bbox,
                    elementType = OCR_ELEMENT_TYPE,
                    label = line.text,
                    confidence = OCR_PROMOTED_CONFIDENCE,
                )
            }
            val elements = yoloElements + promotedElements

            val annotated = if (withAnnotatedImage) renderAnnotated(bitmap, elements) else null

            PerceptionResult(
                ok = true,
                elements = elements,
                sourceWidthPx = bitmap.width,
                sourceHeightPx = bitmap.height,
                annotatedBase64Png = annotated,
                timingsMs = mapOf(
                    "yolo" to yoloMs,
                    "ocr" to ocrMs,
                    // Step-0 breakdown of the headline "yolo" number.
                    "yolo_session_init" to yt.sessionInitMs,
                    "yolo_preprocess" to yt.preprocessMs,
                    "yolo_inference" to yt.inferenceMs,
                    "yolo_postprocess" to yt.postprocessMs,
                    // Long map can't hold a bool — encode cold start as 1/0.
                    "yolo_cold_start" to if (yt.coldStart) 1L else 0L,
                ),
                engine = yolo.activeProvider?.name,
            )
        }
    }

    /**
     * Phase 10 — annotate an arbitrary screenshot with caller-supplied element
     * [groups], each drawn in its own color onto a SINGLE image. `perceive_screen`
     * uses this to emit one combined SoM image where box color encodes source
     * (blue = ui_tree / accessibility, red = omniparser / CV). The PNG is decoded
     * once and copied once regardless of how many groups are drawn.
     */
    override suspend fun drawAnnotations(
        pngBytes: ByteArray,
        groups: List<AnnotationGroup>,
        maxLongSidePx: Int,
    ): String? {
        val src = runCatching {
            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        }.getOrNull() ?: return null
        return runCatching {
            val longSide = max(src.width, src.height)
            val scale = if (maxLongSidePx in 1 until longSide) {
                maxLongSidePx.toFloat() / longSide
            } else {
                1f
            }
            val dstW = max(1, (src.width * scale).toInt())
            val dstH = max(1, (src.height * scale).toInt())
            // Draw the (optionally downscaled) source into one fresh mutable
            // bitmap, then overlay each group's boxes scaled to match. The
            // returned image shrinks; the JSON coords the tool reports do not.
            val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            Canvas(out).also { canvas ->
                canvas.drawBitmap(
                    src,
                    Rect(0, 0, src.width, src.height),
                    Rect(0, 0, dstW, dstH),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                val scaled = groups.map { it.copy(elements = scaleElements(it.elements, scale)) }
                drawAnnotationGroups(canvas, dstW.toFloat(), dstH.toFloat(), scaled)
            }
            encodeBase64Png(out)
        }.getOrNull()
    }

    /** Scale element bboxes for drawing on a downscaled canvas (display only). */
    private fun scaleElements(elements: List<DetectedElement>, scale: Float): List<DetectedElement> =
        if (scale == 1f) {
            elements
        } else {
            elements.map {
                it.copy(
                    bbox = BBox(
                        x1 = (it.bbox.x1 * scale).toInt(),
                        y1 = (it.bbox.y1 * scale).toInt(),
                        x2 = (it.bbox.x2 * scale).toInt(),
                        y2 = (it.bbox.y2 * scale).toInt(),
                    ),
                )
            }
        }

    /** Single-color render (default red). Used by get_annotated_screenshot. */
    private fun renderAnnotated(
        source: Bitmap,
        elements: List<DetectedElement>,
        boxColor: Int = Color.RED,
    ): String {
        val canvasBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        drawAnnotationGroups(
            Canvas(canvasBitmap),
            source.width.toFloat(),
            source.height.toFloat(),
            listOf(AnnotationGroup(elements, boxColor)),
        )
        return encodeBase64Png(canvasBitmap)
    }

    /**
     * Draw every group's boxes, THEN all SoM number tags on top with
     * collision-aware placement.
     *
     * Two-pass by design: drawing all boxes first means a later box's outline
     * can never cross an earlier tag, and computing every tag position together
     * (via [placeSomTags]) means tags in a dense cluster spread across their own
     * boxes' corners instead of stacking on the same top-left pixel. Box color
     * still encodes source (blue = ui_tree, red = omniparser); each tag shares
     * its box's color. Text carries a dark outline so numerals stay legible over
     * any background.
     *
     * [imageW]/[imageH] are the drawing surface size (post-downscale); element
     * bboxes must already be in that same space.
     */
    private fun drawAnnotationGroups(
        canvas: Canvas,
        imageW: Float,
        imageH: Float,
        groups: List<AnnotationGroup>,
    ) {
        // Both sizes are deliberately lean. Every pixel a box outline or a number tag
        // spends is a pixel of the actual UI it hides, and on a dense screen the
        // annotations were competing with the content they annotate. The old floors
        // (3f stroke, 24f text) were what actually bound on a 701 px-wide downscale —
        // the ratios never got a say — so lowering the floors IS the change.
        val textSize = max(20f, imageW / 56f)
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            // Hairline: readable against any background thanks to antialiasing, while
            // no longer swallowing the 1-2 px gaps between adjacent list rows.
            strokeWidth = max(1.5f, imageW / 500f)
            isAntiAlias = true
        }
        val tagBg = Paint().apply { style = Paint.Style.FILL }
        val tagText = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            isFakeBoldText = true
        }
        val tagOutline = Paint().apply {
            color = Color.argb(170, 0, 0, 0)
            this.textSize = textSize
            style = Paint.Style.STROKE
            strokeWidth = max(2f, imageW / 700f)
            isAntiAlias = true
            isFakeBoldText = true
        }

        // Flatten both color groups so the tag pass sees every element at once.
        data class Marked(val element: DetectedElement, val color: Int)
        val marked = groups.flatMap { g -> g.elements.map { Marked(it, g.colorArgb) } }

        // Pass 1 — all boxes.
        for (m in marked) {
            boxPaint.color = m.color
            canvas.drawRect(
                m.element.bbox.x1.toFloat(),
                m.element.bbox.y1.toFloat(),
                m.element.bbox.x2.toFloat(),
                m.element.bbox.y2.toFloat(),
                boxPaint,
            )
        }

        // Pass 2 — place every tag together, then draw on top.
        val requests = marked.map { m ->
            val tw = tagText.measureText(m.element.somId.toString()) + TAG_H_PAD
            TagRequest(box = m.element.bbox.toFRect(), tagWidth = tw, tagHeight = textSize + TAG_V_PAD)
        }
        val placements = placeSomTags(requests, imageW, imageH)
        for (i in marked.indices) {
            val m = marked[i]
            val r = placements[i]
            tagBg.color = (m.color and 0x00FFFFFF) or (220 shl 24)
            canvas.drawRect(r.left, r.top, r.right, r.bottom, tagBg)
            val text = m.element.somId.toString()
            val tx = r.left + TAG_H_PAD / 2f
            val ty = r.top + textSize
            canvas.drawText(text, tx, ty, tagOutline)
            canvas.drawText(text, tx, ty, tagText)
        }
    }

    private fun encodeBase64Png(bmp: Bitmap): String =
        ByteArrayOutputStream().use { bos ->
            // Quality is for JPEG; for PNG the second arg is ignored.
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }

    companion object {
        private const val TAG = "AppPerceptionBridge"
    }
}

/** One OCR-recognized text line in source-image pixels. Android-free so the
 * labeling/promotion logic below runs in plain-JVM unit tests. */
data class OcrLine(val text: String, val bbox: BBox)

/** IoU at or above this: the OCR line "belongs to" a YOLO box — it may label
 * the box, and it is NOT promoted to an element of its own. */
internal const val IOU_FOR_LABEL_MATCH = 0.3f

/** Single glyphs ("0", "×") are overwhelmingly OCR noise; require ≥ 2 chars. */
internal const val MIN_PROMOTED_TEXT_LENGTH = 2

/** Upper bound on promoted text elements per frame — keeps the SoM image
 * readable and the JSON payload bounded on text-dense screens (articles,
 * chat logs). Never applied silently: the bridge logs what it drops. */
internal const val MAX_PROMOTED_OCR_ELEMENTS = 40

/** ML Kit Latin doesn't reliably expose per-line confidence across SDK
 * versions — a fixed value honestly marks these as model-derived (vs 1.0
 * for accessibility-tree facts). */
internal const val OCR_PROMOTED_CONFIDENCE = 0.8f
internal const val OCR_ELEMENT_TYPE = "text"

/** Highest-IoU OCR line for [bbox], or "" when nothing reaches the
 * [IOU_FOR_LABEL_MATCH] threshold (an honest empty label beats a wrong one). */
internal fun bestOcrLabelFor(bbox: BBox, ocrLines: List<OcrLine>): String {
    var bestText = ""
    var bestIou = 0f
    for (line in ocrLines) {
        val iou = bboxIou(bbox, line.bbox)
        if (iou > bestIou) {
            bestIou = iou
            bestText = line.text
        }
    }
    return if (bestIou >= IOU_FOR_LABEL_MATCH) bestText else ""
}

/**
 * OCR lines that landed on NO YOLO box (max IoU < [IOU_FOR_LABEL_MATCH]
 * against every box) and pass the noise filter. These become tappable
 * elements of their own: icon_detect cannot see text-only buttons
 * ("START", "CANCEL"), so discarding unmatched OCR text made them
 * untappable — the chess.com dialog regression. Mirrors upstream
 * OmniParser, which merges OCR regions as first-class elements.
 *
 * Uncapped by design — the caller applies [MAX_PROMOTED_OCR_ELEMENTS]
 * and logs any drop.
 */
internal fun promotableOcrLines(yoloBoxes: List<BBox>, ocrLines: List<OcrLine>): List<OcrLine> =
    ocrLines.filter { line ->
        line.text.length >= MIN_PROMOTED_TEXT_LENGTH &&
            yoloBoxes.none { bboxIou(it, line.bbox) >= IOU_FOR_LABEL_MATCH }
    }

internal fun bboxIou(a: BBox, b: BBox): Float {
    val ix1 = max(a.x1, b.x1)
    val iy1 = max(a.y1, b.y1)
    val ix2 = min(a.x2, b.x2)
    val iy2 = min(a.y2, b.y2)
    if (ix2 <= ix1 || iy2 <= iy1) return 0f
    val inter = (ix2 - ix1) * (iy2 - iy1)
    val union = a.area + b.area - inter
    return inter.toFloat() / union
}

// ── SoM number-tag placement (Android-free, unit-testable geometry) ──────────
//
// The Canvas layer measures a tag's rendered size (`measureText`) and draws it;
// WHERE each tag goes is pure geometry, extracted here so the collision logic
// runs under plain-JVM `./gradlew app:test` with no Canvas/Robolectric.

/** Horizontal / vertical padding baked into a number tag's background rect.
 * Kept in sync between the drawing pass and the placement math. */
internal const val TAG_H_PAD = 12f
internal const val TAG_V_PAD = 6f

/** An axis-aligned float rectangle in drawing (post-downscale) pixel space. */
internal data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(o: FRect): Boolean =
        left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** One number tag to position: the element's box + the tag's rendered size. */
internal data class TagRequest(val box: FRect, val tagWidth: Float, val tagHeight: Float)

/**
 * Choose a non-overlapping screen position for each SoM number tag.
 *
 * Every candidate sits ON the tag's OWN box — a tag never drifts toward a
 * neighbour, because a number nearer another element's box than its own corrupts
 * the model→som_id mapping the annotated image exists to convey. That constraint
 * is absolute: two numbers touching is a readability problem, a number sitting on
 * the wrong element is a WRONG TAP. So crowding is resolved by shuffling a tag
 * around its own box, never by pushing it off.
 *
 * Among its own anchors we take the first that clears every already-placed tag.
 * When a genuinely dense cluster leaves no clean spot we take the anchor that
 * overlaps the LEAST, rather than falling back to the default corner: the default
 * is where everything already piles up, so blindly returning to it turned "a bit
 * crowded" into an unreadable stack on exactly the screens that are hardest to
 * read. Least-overlap costs one extra area computation and degrades gracefully.
 *
 * Smaller boxes are positioned first: they are the crowded ones with the fewest
 * free anchors, so they get first pick. Result is returned in input order.
 */
internal fun placeSomTags(tags: List<TagRequest>, imageW: Float, imageH: Float): List<FRect> {
    val result = arrayOfNulls<FRect>(tags.size)
    val placed = ArrayList<FRect>(tags.size)
    val order = tags.indices.sortedBy { boxArea(tags[it].box) }
    for (i in order) {
        val candidates = tagCandidates(tags[i], imageW, imageH)
        val chosen = candidates.firstOrNull { c -> placed.none { it.intersects(c) } }
            ?: candidates.minByOrNull { c -> placed.sumOf { overlapArea(it, c).toDouble() } }
            ?: candidates.first()
        result[i] = chosen
        placed.add(chosen)
    }
    return result.map { requireNotNull(it) }
}

private fun boxArea(b: FRect): Float = (b.right - b.left) * (b.bottom - b.top)

/** Area shared by two tag rects; 0 when they merely touch or miss entirely. */
private fun overlapArea(a: FRect, b: FRect): Float {
    val w = min(a.right, b.right) - max(a.left, b.left)
    val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    return if (w <= 0f || h <= 0f) 0f else w * h
}

/**
 * Candidate tag rects for one box, best-first: outside-top-left (the classic SoM
 * position), then the other top anchors, then inside the box.
 *
 * All ten stay within the box's HORIZONTAL span and no lower than its bottom edge,
 * so a tag is always either hugging its own box's top edge from just outside or
 * sitting fully inside it. Centre anchors were added because corners-only left a
 * tight row of same-height siblings with just two usable spots each; the midpoints
 * are free real estate that costs nothing to offer. Nothing is placed BELOW the
 * box — that space belongs to the next row, and a number floating above someone
 * else's control is the one failure this whole function exists to prevent.
 *
 * Each is clamped fully inside the image so a tag on an edge box never runs off.
 */
private fun tagCandidates(t: TagRequest, imageW: Float, imageH: Float): List<FRect> {
    val w = t.tagWidth
    val h = t.tagHeight
    val b = t.box
    val midX = (b.left + b.right) / 2f - w / 2f
    val midY = (b.top + b.bottom) / 2f - h / 2f
    val anchors = listOf(
        b.left to (b.top - h),           // outside top-left (default)
        b.left to b.top,                 // inside top-left
        (b.right - w) to (b.top - h),    // outside top-right
        (b.right - w) to b.top,          // inside top-right
        midX to (b.top - h),             // outside top-centre
        midX to b.top,                   // inside top-centre
        b.left to (b.bottom - h),        // inside bottom-left
        (b.right - w) to (b.bottom - h), // inside bottom-right
        midX to (b.bottom - h),          // inside bottom-centre
        midX to midY,                    // dead centre — last resort
    )
    val maxX = max(0f, imageW - w)
    val maxY = max(0f, imageH - h)
    return anchors.map { (x, y) ->
        val cx = x.coerceIn(0f, maxX)
        val cy = y.coerceIn(0f, maxY)
        FRect(cx, cy, cx + w, cy + h)
    }
}

private fun BBox.toFRect(): FRect = FRect(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
