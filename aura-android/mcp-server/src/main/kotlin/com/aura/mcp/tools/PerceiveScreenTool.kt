package com.aura.mcp.tools

import com.aura.mcp.bridge.AnnotationGroup
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.cache.CvMemo
import com.aura.mcp.cache.LastSeenScreen
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.cache.ScreenActivity
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * `perceive_screen`: look at the screen with on-device vision only (OmniParser's YOLO boxes
 * plus OCR text), for the surfaces the accessibility tree cannot describe: web pages, maps,
 * games, canvases, and apps that hide their controls from accessibility.
 *
 * - Contract: every box gets a som_id from [PerceptionCache.VISION_ID_BASE] + 1 upward, so a
 *   vision id can never be mistaken for a `read_screen` id. The ids replace the cached ones
 *   until the next look; `tap(som_id)` then resolves them like any other.
 * - Fails: a capture refused, a CV timeout past [CV_BUDGET_MS] or a CV error returns an error
 *   that sends the model back to `read_screen`. There is no tree fallback here on purpose:
 *   `read_screen` is the tree.
 * - Reads: one screenshot and one tree read, in parallel. The tree is never shown; it gives
 *   the screen signature (staleness, the CV memo), the loading flag and offscreen text.
 * - Why vision only: the tree half duplicated `read_screen` at the cost of a second id space
 *   for the same elements. The model reads the tree with `read_screen` and comes here when
 *   that look says ESCALATE or its target is missing.
 */
internal fun Server.registerPerceiveScreenTool(
    screenshotBridge: ScreenshotBridge,
    uiTreeBridge: UiTreeBridge,
    perceptionBridge: PerceptionBridge,
    perceptionCache: PerceptionCache,
    cvMemo: CvMemo = CvMemo(),
    lastSeenScreen: LastSeenScreen = LastSeenScreen(),
) {
    scopedTool(
        name = "perceive_screen",
        description = """
            Look at the screen with on-device vision: a screenshot with numbered boxes found by an object detector and OCR, and a list of them. Slower than read_screen; use it when read_screen says ESCALATE, or your target is not in read_screen's list.

            Each line is "som_id label", with ? when the detector is unsure. Ids start at 1001, so they never clash with read_screen's. Tap by som_id; judge an unlabelled box from the picture.
            """.trimIndent(),
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf("description" to stringSchema("What you are looking for, e.g. 'search bar'. Logged; does not change the result.")),
            ),
        ),
    ) { request ->
        val description = request.arguments?.stringArg("description").orEmpty()
        coroutineScope {
            val shot = async(Dispatchers.IO) { captureBytes(screenshotBridge) }
            val tree = async(Dispatchers.IO) {
                val snap = uiTreeBridge.snapshot()
                val json = if (snap.ok) snap.payloadJson else "{}"
                // Sampled next to the read it describes, not after the CV pass, which can take
                // ~1.5 s and would hide any event fired inside it.
                TreeFacts(
                    signature = ScreenSignature.of(json),
                    activity = ScreenActivity.meaningfulCount,
                    loading = snap.ok && UiTreeHeuristics.summarize(json).loadingIndicatorPresent,
                    offscreen = if (snap.ok) ScreenText.offscreen(json) else emptyList(),
                )
            }
            val (bytes, captureErr) = shot.await()
            if (bytes == null) {
                tree.cancel()
                return@coroutineScope captureErr ?: errorResult("Screen capture failed.")
            }
            val facts = tree.await()
            lastSeenScreen.set(facts.signature)

            // Same screen as the last vision pass: reuse its boxes, so a re-look costs a capture.
            val memo = cvMemo.get(facts.signature)
            val found = memo?.elements ?: withTimeoutOrNull(CV_BUDGET_MS) {
                perceptionBridge.detectElements(pngBytes = bytes, withOcr = true, withAnnotatedImage = false)
            }?.takeIf { it.ok }?.also {
                cvMemo.put(facts.signature, it.elements, it.sourceWidthPx, it.sourceHeightPx, it.engine)
            }?.elements
            if (found.isNullOrEmpty()) {
                return@coroutineScope errorResult(
                    if (found == null) {
                        "Vision did not finish in time. Use read_screen, or try again."
                    } else {
                        "Vision found nothing on this screen. It may be blank or still drawing; wait_for, then look again."
                    },
                )
            }

            val boxes = numberVisionBoxes(found)
            perceptionCache.update(boxes, facts.signature, facts.activity, baseline = false)
            val image = perceptionBridge.drawAnnotations(
                pngBytes = bytes,
                groups = listOf(AnnotationGroup(boxes, COLOR_VISION)),
                maxLongSidePx = IMAGE_MAX_LONG_SIDE_PX,
            )
            CallToolResult(
                content = listOfNotNull(
                    image?.let { ImageContent(data = it, mimeType = "image/png") },
                    TextContent(visionText(description, boxes, facts.loading, facts.offscreen, memo != null)),
                ),
            )
        }
    }
}

/** The tree facts a vision look still needs; the tree's elements are never shown. */
private data class TreeFacts(
    val signature: ScreenSignature.Signature,
    val activity: Long,
    val loading: Boolean,
    val offscreen: List<String>,
)

/** Vision boxes in reading order (top to bottom, left to right), numbered from 1001. */
internal fun numberVisionBoxes(found: List<DetectedElement>): List<DetectedElement> =
    found.sortedWith(compareBy({ it.bbox.y1 }, { it.bbox.x1 }))
        .mapIndexed { i, e -> e.copy(somId = PerceptionCache.VISION_ID_BASE + i + 1) }

/** What the model reads beside the picture: one line per box, `som_id label[ ?]`. */
internal fun visionText(
    description: String,
    boxes: List<DetectedElement>,
    loading: Boolean,
    offscreen: List<String>,
    sameAsLast: Boolean,
): String = buildString {
    if (loading) appendLine("STILL LOADING: a spinner is showing, so what you need may not be drawn yet. wait_for, then look again.")
    append("VISION ${boxes.size} boxes")
    if (description.isNotBlank()) append(" (looking for: $description)")
    if (sameAsLast) append("; the screen has not changed since the last vision look")
    appendLine()
    boxes.forEach { e ->
        append(e.somId)
        if (e.label.isNotBlank()) append(' ').append(e.label.replace('\n', ' ').take(MAX_LABEL))
        if (e.confidence < LOW_CONFIDENCE) append(" ?")
        appendLine()
    }
    if (offscreen.isNotEmpty()) append("OFFSCREEN (exists, not visible, no som_id): ").append(offscreen.joinToString(" | "))
}.trimEnd()

private const val MAX_LABEL = 60

/** Below this, a vision box is marked `?`. */
private const val LOW_CONFIDENCE = 0.5f

/** Vision deadline. Past it, perceive_screen returns an error instead of blocking the agent (a 10 s cold/GC spike was seen). */
private const val CV_BUDGET_MS = 4_000L

/** Role colour for an accessibility-tree element — also what `read_screen`'s image draws. */
internal fun roleColor(e: DetectedElement): Int {
    return when {
        e.editable -> COLOR_EDITABLE
        e.checked != null -> COLOR_CHECKABLE
        e.scrollable -> COLOR_SCROLLABLE
        !e.interactive -> COLOR_PASSIVE
        else -> COLOR_TAPPABLE
    }
}

/**
 * Below this, the accessibility tree is not describing the screen — WebView, Canvas,
 * Maps, a game surface. Shared with `read_screen` ([ReadScreen.escalateReason]) on
 * purpose: two different thresholds would mean a screen one tool reads happily is one
 * the other refuses to trust.
 */
internal const val MIN_UI_TREE_ELEMENTS = 3
/**
 * Long-side cap for the RETURNED image only — element coordinates stay full-res.
 *
 * 1568 is the largest long side vision models process without re-tiling. On a 1240x2772
 * phone that renders 701x1568 (was 572x1280 at the old cap of 1280) — +22% linear
 * resolution for ~490 more image tokens.
 *
 * The trade is deliberate: the payload is now positional short-form and carries no
 * bboxes, so the PICTURE is where the model resolves which box is which. Since we now
 * annotate every visible element rather than a filtered subset, legibility — not
 * payload size — is the binding constraint, and this is the right place to spend the
 * tokens the lean format freed.
 */
internal const val IMAGE_MAX_LONG_SIDE_PX = 1568

// ── SoM box colors (ARGB) ────────────────────────────────────────────────────────
// Color encodes the element's ROLE, not merely its source: the picture is a channel we
// already pay for, so meaning pushed into it is free, while meaning pushed into JSON is
// billed on every call. Blue and red keep their previous meanings so existing behaviour
// still reads correctly.
//
// Magenta rather than purple for scrollable — at a 3-4 px stroke purple is not reliably
// separable from blue.
private val COLOR_TAPPABLE: Int = 0xFF2196F3.toInt() // blue   — tap target (tree)
private val COLOR_EDITABLE: Int = 0xFF00C853.toInt() // green  — type here, do not tap
private val COLOR_SCROLLABLE: Int = 0xFFD500F9.toInt() // magenta — scroll target
private val COLOR_CHECKABLE: Int = 0xFFFFAB00.toInt() // amber  — has an on/off state
private val COLOR_PASSIVE: Int = 0xFF9E9E9E.toInt() // grey   — no action flags
private val COLOR_VISION: Int = 0xFFF44336.toInt() // red    — CV guess, not the tree
