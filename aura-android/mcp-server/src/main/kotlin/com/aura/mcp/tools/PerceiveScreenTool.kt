package com.aura.mcp.tools

import com.aura.mcp.bridge.AnnotationGroup
import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.bridge.PerceptionBridge
import com.aura.mcp.bridge.ScreenshotBridge
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.cache.CvMemo
import com.aura.mcp.cache.LastSeenScreen
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.cache.PerceptionEscalation
import com.aura.mcp.cache.ScreenActivity
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 10 — dual-track perception.
 *
 * `perceive_screen` is the **escalation** call. It was the default until
 * 2026-08-18, when `read_screen` ([registerReadScreenTool]) gave the agent a
 * settled tree read at ~0.7k tokens and no image — which answers most of what
 * a look is for. This one earns its cost when the question is genuinely
 * visual (a colour, a picture, an unlabelled icon) or when the accessibility
 * tree cannot describe the surface at all.
 *
 * Every invocation returns one annotated screenshot + a structured element
 * list, so the agent can choose its tap target from both visual and structural
 * ground truth at once. Higher cost than `read_screen` alone (~500 ms vs
 * ~50 ms, plus an image in context), and markedly fewer tap errors where the
 * picture is what settles it.
 *
 * Box-source policy:
 *
 *   1. **UI tree is primary.** Accessibility bounds are factual, not
 *      probabilistic. If the tree yields ≥ [MIN_UI_TREE_ELEMENTS]
 *      interactive elements, those become the SoM boxes.
 *
 *   2. **OmniParser fills the gaps.** Run YOLOv8 + ML Kit OCR on the
 *      same screenshot. For every CV-detected box, drop it if it
 *      overlaps an existing UI-tree box (UI tree wins on overlap —
 *      strategy "a"). Keep the rest, renumber, append.
 *
 *   3. **OmniParser is the sole source** when the UI tree is empty,
 *      validation-failed, or below the threshold (WebView, Canvas,
 *      Maps, games, custom-rendered surfaces).
 *
 * Every returned element carries a `source` field — `"ui_tree"` /
 * `"omniparser"` — so the caller knows where each box came from and can
 * weight its trust accordingly.
 *
 * ### Where each fact lives (2026-08-26, design Step 0)
 *
 * This description was 1,326 tokens on **every** request — 15.5% of the whole tool
 * surface — for a tool called once across the 30-task suite. Most of it was the
 * perception ladder, stated here for the third time. The one-home assignment:
 *
 * | Fact | Home | Why there |
 * |---|---|---|
 * | read_screen is the default look | `SYSTEM_PROMPT` doctrine | true before any tool is chosen |
 * | when to escalate here from a read | `read_screen`'s description + its `ESCALATE` payload | the decision is made holding a read_screen result |
 * | when to escalate tree-only → `full` | this tool's `detail` schema | the decision is that argument's value |
 * | som_id → tap, never raw coordinates | `Doctrine.target_by_som_id` | the only home present on a read_screen-only run, where there is no image |
 * | the image is replaced next turn — note the som_id now | `VISION_REINJECT_NOTE` | arrives with the thing it describes |
 * | end_session's success enforcement (which goal_types, what the read must show) | `end_session`'s description | the decision is made holding that tool |
 * | `e` array shape, box colours, flags, `offscreen` | here | nothing else reads this payload |
 *
 * The rule that keeps it this way: **a routing rule added here must be deleted from
 * its other home in the same change.** `scripts/tool_desc_budget.py` measures the
 * result (`--compare` against a saved baseline); note that it counts the description
 * and the `inputSchema` strings TOGETHER, because moving prose between them is not a cut.
 */
internal fun Server.registerPerceiveScreenTool(
    screenshotBridge: ScreenshotBridge,
    uiTreeBridge: UiTreeBridge,
    perceptionBridge: PerceptionBridge,
    perceptionCache: PerceptionCache,
    escalation: PerceptionEscalation = PerceptionEscalation(),
    cvMemo: CvMemo = CvMemo(),
    lastSeenScreen: LastSeenScreen = LastSeenScreen(),
) {
    scopedTool(
        name = "perceive_screen",
        // Budget: ~300 tok of description (design Step 0). Was 1,120; now 327, and 433 with
        // the inputSchema, which is the figure that matters — both ride the same request.
        // Everything cut was said somewhere the model already reads on the same request —
        // see this file's KDoc for which fact lives where. Do not re-add a routing rule here
        // without deleting it from its other home; `scripts/tool_desc_budget.py` is the meter.
        description = """
            Look at the screen with an annotated screenshot and a list of every element. Costs more than read_screen: use it when read_screen could not show what you need.

            `e` lists the elements. An element's position in `e` is its som_id (first entry = 1), the number on its box. Each entry is [center_x, center_y, name, flags]; name and flags are left out when empty. Target by som_id; the coordinates are for reference. A name like "glow_subnav_ingress" is an app's internal id for an unlabelled control — judge it from the picture.

            BOX COLOUR — the element's role:
              blue tap · green text field (type, don't tap) · magenta scrolls · amber toggle
              grey nothing declared, usually still tappable
              red found by on-device vision, a guess — when red and another colour disagree, trust the other

            FLAGS, only when true: e editable · c on · o off · d disabled · f focused · l long-press · ? low-confidence vision box · w container — aim at a box inside it

            `offscreen`, when present, is text that exists but is not visible (the rest of a carousel). It has no som_id and cannot be tapped.

            `perception_tier` says which pass ran: "tree_only" or "full".
            """.trimIndent(),
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "description" to stringSchema(
                        "What you are looking for, e.g. 'search bar'. Logged for audit; " +
                            "does not change the result.",
                    ),
                    // The sole home for the escalate-to-full rule. It used to be stated
                    // here AND in the description above, ~180 tok for one parameter, and a
                    // reader had to notice the two copies agreed. This is the decision
                    // point: you are choosing this argument's value.
                    "detail" to stringSchema(
                        "Optional. \"full\" forces the visual pass (YOLOv8 + OCR). Pass it " +
                            "after a tree-only view where your target had no box, no box of " +
                            "its OWN (the box spanned a whole row or several items), a box " +
                            "was visibly misplaced, or you tapped a box and nothing " +
                            "happened. Nothing detects these for you. Default: tree-only.",
                    ),
                ),
            ),
            required = listOf("description"),
        ),
    ) { request ->
        val description = request.arguments?.stringArg("description")
            ?: return@scopedTool errorResult("perceive_screen requires a 'description' argument")
        val forcedFull = request.arguments?.stringArg("detail") == "full"

        return@scopedTool coroutineScope {
            // ── Stage 1: capture ∥ ui_tree (independent — run concurrently) ──
            val captureDeferred = async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val r = captureBytes(screenshotBridge)
                r to (System.currentTimeMillis() - t0)
            }
            val uiTreeDeferred = async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val snap = uiTreeBridge.snapshot()
                val els: List<DetectedElement> = if (snap.ok) {
                    UiTreeToElements.extract(snap.payloadJson, MIN_UI_TREE_ELEMENTS)
                } else {
                    emptyList()
                }
                val summary = if (snap.ok) UiTreeHeuristics.summarize(snap.payloadJson) else null
                val foreground = summary?.foregroundApp?.ifBlank { null }
                val screenHeight = summary?.screenHeightPx ?: 0
                // One extra pass over a tree we already paid for (~1 ms). Identifies
                // this screen for the tier policy, the CV memo, and som_id validity.
                val signature = ScreenSignature.of(if (snap.ok) snap.payloadJson else "{}")
                // Sampled HERE, next to the read it describes — not after the CV pass,
                // which can take ~1.5 s and would hide any event fired inside it.
                val activityAtRead = ScreenActivity.meaningfulCount
                TreeSnapshot(
                    els, foreground, screenHeight, signature, activityAtRead,
                    System.currentTimeMillis() - t0,
                    loadingIndicatorPresent = summary?.loadingIndicatorPresent == true,
                    screenText = if (snap.ok) ScreenText.extract(snap.payloadJson) else emptyList(),
                    offscreenText = if (snap.ok) ScreenText.offscreen(snap.payloadJson) else emptyList(),
                )
            }

            val (captureRes, captureMs) = captureDeferred.await()
            val (bytes, captureErr) = captureRes
            if (bytes == null) {
                uiTreeDeferred.cancel()
                return@coroutineScope captureErr!!
            }
            val treeSnapshot = uiTreeDeferred.await()
            val (
                uiTreeElements, foregroundApp, screenHeightPx, signature, activityAtRead,
                uiTreeMs, stillLoading,
            ) = treeSnapshot
            val uiTreeUsable = uiTreeElements.isNotEmpty()
            // A perceive IS a look — the next gesture's change detection must compare
            // against this, not against whatever the previous settle saw.
            lastSeenScreen.set(signature)

            // ── Tier decision. Tree-only is the DEFAULT: the model looks at the boxes
            // and calls detail="full" itself when its target has no box, or no box of
            // its own. The server no longer predicts that — the two overrides below are
            // the failures the model provably cannot see from the picture.
            //
            // A tree that LOOKS rich but leaves a large empty vertical band is an
            // app hiding its UI from accessibility services (Swiggy et al.) — the screen
            // looks densely numbered, so the model grabs a nearby number rather than
            // noticing anything is missing. Force the vision tier so OmniParser recovers
            // the hidden controls.
            val degraded = TreeSufficiency.looksDegraded(uiTreeElements, screenHeightPx)
            // Looking again at the same screen we just served cheap: the model could not
            // act on that view, so serving it again unchanged is never the answer. The
            // LAYOUT lane, not content: a ticking label must not disguise a re-look as a
            // new screen (see PerceptionEscalation.repeatOfCheapView).
            val layoutHash = signature.layout.takeIf { !signature.treeBlind }
            val repeat = escalation.repeatOfCheapView(layoutHash)
            val escalationReason = when {
                degraded -> REASON_TREE_DEGRADED
                repeat -> PerceptionEscalation.REASON_REPEAT
                else -> null
            }
            val treeOnly = !forcedFull &&
                escalationReason == null &&
                TreeSufficiency.sufficient(uiTreeElements)

            // ── Stage 2: OmniParser (YOLO ∥ OCR inside the bridge), capped by a
            // budget so a slow/stuck CV pass degrades to ui_tree-only instead of
            // freezing the agent — skipped entirely on the tree-only tier. ──
            // Same screen as the last full pass? Reuse its detections. The model
            // re-perceiving an unchanged screen used to be BOTH the most expensive
            // path and the least informative one; now it costs a capture.
            val memoHit = if (treeOnly) null else cvMemo.get(signature)
            val cvStart = System.currentTimeMillis()
            val cv = if (treeOnly || memoHit != null) {
                null
            } else {
                withTimeoutOrNull(CV_BUDGET_MS) {
                    perceptionBridge.detectElements(
                        pngBytes = bytes,
                        withOcr = true,
                        withAnnotatedImage = false,
                    )
                }
            }
            val cvMs = System.currentTimeMillis() - cvStart
            val cvTimedOut = cv == null && memoHit == null && !treeOnly
            if (cv != null && cv.ok) {
                cvMemo.put(signature, cv.elements, cv.sourceWidthPx, cv.sourceHeightPx, cv.engine)
            }
            val cvElements = memoHit?.elements ?: if (cv != null && cv.ok) cv.elements else emptyList()

            // ── Stage 3: merge (UI tree bounds win on overlap; a blank tree
            // label borrows the overlapping CV element's label instead of
            // losing it outright — see [mergeElements]) ──
            val merged = mergeElements(
                uiTreeElements = uiTreeElements,
                cvElements = cvElements,
                uiTreeUsable = uiTreeUsable,
                screenHeightPx = screenHeightPx,
            ).toMutableList()

            if (merged.isEmpty()) {
                return@coroutineScope errorResult(
                    "No interactive elements found by either UI tree or OmniParser" +
                        (if (cvTimedOut) " (OmniParser timed out after $CV_BUDGET_MS ms)" else "") +
                        ". The screen may be empty, fully obscured, or the perception models failed.",
                )
            }

            val renumbered = renumberAndFlagWrappers(merged, screenHeightPx)

            // Update the perception cache so tap/double_tap/long_press can resolve som_id
            // → (center_x, center_y) server-side without trusting model eyeballing.
            perceptionCache.update(renumbered.map { it.element }, signature, activityAtRead)

            // ── Stage 4: one image, boxes coloured by ROLE, downscaled for payload ──
            // Grouped by colour rather than by source: colour is a free channel (it
            // rides on an image we already send) whereas the same distinction in JSON is
            // billed on every call. Groups are keyed by colour so the bridge still just
            // draws whatever colour it is handed.
            val annoStart = System.currentTimeMillis()
            val groups = renumbered
                .groupBy { roleColor(it) }
                .map { (color, items) -> AnnotationGroup(items.map { it.element }, color) }
            val annotated = if (groups.isNotEmpty()) {
                perceptionBridge.drawAnnotations(
                    pngBytes = bytes,
                    groups = groups,
                    maxLongSidePx = IMAGE_MAX_LONG_SIDE_PX,
                )
            } else {
                null
            }
            val annotateMs = System.currentTimeMillis() - annoStart

            // ── Stage 5: JSON payload (coords are FULL-resolution device pixels,
            // unaffected by the image downscale above) ──
            val elementsJson = buildJsonArray {
                for (m in renumbered) {
                    add(detectedElementShortJson(m.element))
                }
            }

            // Dimensions come from the decoded screenshot (bitmap.width/height via the
            // CV pass). On a CV timeout we don't have them — report 0 (unknown) rather
            // than guess; coords in `elements` are unaffected either way.
            val sourceWidth = (cv?.sourceWidthPx ?: memoHit?.sourceWidthPx)?.takeIf { it > 0 } ?: 0
            val sourceHeight = (cv?.sourceHeightPx ?: memoHit?.sourceHeightPx)?.takeIf { it > 0 } ?: 0
            val uiCount = renumbered.count { it.source == "ui_tree" }
            val cvCount = renumbered.count { it.source == "omniparser" }
            // Elements that reached the model with no label even after
            // UiTreeToElements' descendant-borrowing and the merge splice above —
            // the genuinely hollow ones. Diagnostic only; doesn't change the
            // response shape or the tier decision.
            val unlabeledCount = renumbered.count { it.element.label.isBlank() }
            val omniStatus = when {
                treeOnly -> "skipped_tree_sufficient"
                memoHit != null -> "cached_screen_unchanged"
                cvTimedOut -> "timeout"
                cv?.ok != true -> "error"
                cvCount == 0 -> "empty"
                else -> "ok"
            }

            // Record what tier was actually served, so a re-look at the same screen can
            // be told apart from a first look at a new one.
            escalation.onServed(
                if (treeOnly) PerceptionEscalation.Tier.CHEAP else PerceptionEscalation.Tier.FULL,
                layoutHash,
            )

            // Metadata is deliberately thin. Anything CONSTANT across calls belongs in
            // the tool DESCRIPTION — sent once per conversation and cached — not in the
            // result, which is billed every call. The legend and the how-to-read-boxes
            // text used to cost ~114 tokens on every single perceive to repeat something
            // the model already had. What survives here is only what CHANGES and what
            // changes a decision. Diagnostics (timings, ratios, categories) moved out;
            // they serve the developer reading a trace, not the agent mid-task.
            val payload = buildJsonObject {
                put("element_count", renumbered.size)
                put("ui_tree_count", uiCount)
                put("omniparser_count", cvCount)
                put("omniparser_status", omniStatus)
                put("perception_tier", if (treeOnly) "tree_only" else "full")
                // Same key the post-action observation uses, because the model is
                // already instructed to trust that name (AuraInstructions). Computed
                // here all along and never returned — so a half-drawn screen was
                // indistinguishable from a finished one, and the boxes of a screen
                // mid-load look exactly like the boxes of a screen that has none.
                // Deliberately NOT an escalation trigger: CV cannot find what has not
                // rendered, so the answer is to wait, not to spend 1.5 s on a spinner.
                put("loading_indicator_present", stillLoading)
                when {
                    forcedFull -> put("escalated_because", "model_requested")
                    escalationReason != null -> put("escalated_because", escalationReason)
                }
                // Told plainly, because the useful next move is to ACT or SCROLL, not
                // to look again — a third identical perceive is a planning problem no
                // amount of extra pixels solves.
                if (memoHit != null) {
                    put("screen_unchanged_since_last_perceive", true)
                }
                put("source_width_px", sourceWidth)
                put("source_height_px", sourceHeight)
                // `e` not `elements`: the key is repeated once per response and the
                // shape is documented in the tool description.
                put("e", elementsJson)
                // Content that EXISTS but is not on screen — the rest of a carousel or
                // a horizontally scrolled row. No coordinates and no som_id: these are
                // not targets, they are the answer to "what else is over there", which
                // the agent previously could only get by scrolling blind.
                if (treeSnapshot.offscreenText.isNotEmpty()) {
                    put(
                        "offscreen",
                        buildJsonArray { treeSnapshot.offscreenText.forEach { add(JsonPrimitive(it)) } },
                    )
                }
            }

            // One combined image, then the JSON list. A leading label TextContent
            // states the color legend so the caller can read box source by color.
            val content = buildList {
                annotated?.let {
                    // Leads the block: a still-loading screen invalidates everything
                    // below it, so the model must read this before the boxes.
                    if (stillLoading) {
                        add(
                            TextContent(
                                "⚠ THIS SCREEN IS STILL LOADING (a spinner/progress bar is " +
                                    "showing). The boxes below are whatever existed mid-load — " +
                                    "usually just the app's permanent chrome (nav bar, header), " +
                                    "not the content you are looking for. Do NOT tap one hoping " +
                                    "it is your target. Call wait_for, then perceive_screen again.",
                            ),
                        )
                    }
                    // Only the part that VARIES per call. The colour legend and the
                    // how-to-read-a-box rules are constant, so they live in the tool
                    // description where they are sent once and cached, rather than being
                    // re-billed on every perceive.
                    add(
                        TextContent(
                            if (treeOnly) {
                                "TREE-ONLY view ($uiCount boxes, no visual AI ran) — these are the " +
                                    "app's own accessibility claims, so VERIFY that ONE box sits " +
                                    "exactly on '$description' before tapping. Call " +
                                    "perceive_screen(detail=\"full\") if it has no box, no box of " +
                                    "its OWN (the nearest box spans several items or the whole " +
                                    "row/card), or a box looks misplaced. Do not tap a guess."
                            } else {
                                "$uiCount tree boxes, $cvCount vision boxes. Find '$description', " +
                                    "then tap(som_id=N)."
                            },
                        ),
                    )
                    add(ImageContent(data = it, mimeType = "image/png"))
                }
                add(TextContent(payload.toString()))
            }

            CallToolResult(content = content, isError = false)
        }
    }
}

internal data class MergedElement(
    val element: DetectedElement,
    val source: String,
)

/**
 * Merge ui_tree elements (bounds/interactivity source of truth — exact, from
 * the accessibility system) with CV elements (bounds are model estimates).
 *
 * A CV element is absorbed into (never kept as a separate box alongside) a
 * ui_tree element it matches by EITHER of two geometric tests:
 *  - [IOU_OVERLAP_DROP] — comparable-size boxes describing the same target
 *    (the original dedup rule, unchanged).
 *  - [CONTAINMENT_LABEL_THRESHOLD] — the CV box sits almost entirely inside a
 *    MUCH bigger tree box. IOU alone misses this: a small element fully
 *    contained in a huge container still scores a tiny IOU (intersection ÷
 *    union, and union ≈ the huge container's area), even at 100% containment.
 *    This is exactly the Settings Wi-Fi toggle row shape — the clickable tree
 *    box spans the whole row (no label of its own) while CV correctly finds
 *    the small switch inside it (IOU ≈ 0.05, containment = 1.0).
 *
 * Either way, if the matched ui_tree element carries no label of its own, the
 * CV element's label is spliced onto it before being dropped — so a correct
 * CV finding is never silently thrown away purely because the more-trusted
 * tree box it landed in happened to be anonymous.
 *
 * Pure function: no coroutines, no bridge calls — independently unit-testable.
 */
/**
 * State flags, one character each, emitted only when true.
 *
 * Deliberately redundant with box colour for [DetectedElement.editable] — see
 * [elementFlags]'s call site and the rule below.
 */
internal fun elementFlags(e: DetectedElement): String = buildString {
    // `e` duplicates the green box, on purpose. If the model misreads a colour and taps
    // a scrollable, nothing happens and it NOTICES. If it misreads a text field it taps
    // instead of typing, gets no error, and has no way to learn what went wrong. The
    // failures that are silent get belt AND braces; the ones that announce themselves
    // ride on colour alone. One character is a cheap price for that asymmetry.
    if (e.editable) append('e')
    when (e.checked) {
        true -> append('c')
        false -> append('o')
        null -> {}
    }
    if (!e.enabled) append('d')
    if (e.wrapper) append('w')
    if (e.focused) append('f')
    if (e.longClickable) append('l')
    // Low-confidence CV guesses only; tree elements are factual (confidence 1.0).
    if (e.confidence < LOW_CONFIDENCE) append('?')
}

/**
 * Short-form wire shape: `[center_x, center_y, name, flags]`.
 *
 * **`som_id` is the array index** (first entry is som_id 1). That is only safe because
 * every annotated box is now emitted — the "draw it, send it" invariant. If anything
 * ever filters this list again the numbering desynchronises SILENTLY and the agent taps
 * the wrong thing, which is why [PerceiveShortFormTest] pins it.
 *
 * Everything dropped from the old object form is still held server-side in
 * [com.aura.mcp.cache.PerceptionCache]; none of it is destroyed, it is simply not
 * shipped on every call. `bbox` in particular is redundant with the picture: the box is
 * drawn, and gestures resolve through the cache rather than model-supplied coordinates.
 */
internal fun detectedElementShortJson(e: DetectedElement): JsonArray = buildJsonArray {
    add(JsonPrimitive(e.bbox.centerX))
    add(JsonPrimitive(e.bbox.centerY))
    val flags = elementFlags(e)
    // Trailing fields are omitted, so a name must be present whenever flags are.
    if (e.label.isNotBlank() || flags.isNotEmpty()) add(JsonPrimitive(e.label))
    if (flags.isNotEmpty()) add(JsonPrimitive(flags))
}

/** Below this, a CV detection is flagged `?` — the tree is always 1.0. */
private const val LOW_CONFIDENCE = 0.5f

/**
 * Assign contiguous som_ids in draw order and mark the boxes that are too big to be a
 * precise target.
 *
 * **The som_id IS the array index** (`somId == index + 1`), because the wire format drops
 * the id and relies on position. That coupling is the one failure in this pipeline that
 * is completely silent — a desynchronised list does not error, it taps the wrong thing —
 * so the renumber lives in one tested function rather than inline at the call site.
 *
 * Wrapper marking uses the SAME ceiling as absorption ([mayAbsorbByContainment]) rather
 * than [TreeSufficiency.CONTAINER_HEIGHT_FRACTION]. One idea, one number: "bigger than a
 * row" is exactly what makes a box both unfit to swallow a CV detection and unfit to be
 * aimed at. Inheriting the 0.6 page-host threshold here would have left the 49%-tall
 * advert wrappers unflagged — surviving absorption but still offered to the model as
 * ordinary blue tap targets covering a third of the screen, which is the opposite of the
 * "flag, don't filter" intent.
 */
internal fun renumberAndFlagWrappers(
    merged: List<MergedElement>,
    screenHeightPx: Int,
): List<MergedElement> = merged.mapIndexed { idx, m ->
    MergedElement(
        element = m.element.copy(
            somId = idx + 1,
            wrapper = !mayAbsorbByContainment(m.element.bbox, screenHeightPx),
        ),
        source = m.source,
    )
}

/**
 * Which colour a box is drawn in — the element's ROLE, most-specific first.
 *
 * Order matters: an editable field is often also clickable, and typing is what the agent
 * must do, so editable wins. Vision-derived boxes keep their own colour regardless of
 * role, because "is this a fact or a guess" outranks "what kind of thing is it".
 */
internal fun roleColor(m: MergedElement): Int =
    if (m.source == "omniparser") COLOR_VISION else roleColor(m.element)

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

internal fun mergeElements(
    uiTreeElements: List<DetectedElement>,
    cvElements: List<DetectedElement>,
    uiTreeUsable: Boolean,
    /** Screen height in device px; 0 when unknown (containment then behaves as before). */
    screenHeightPx: Int = 0,
): List<MergedElement> {
    val tree = uiTreeElements.map { MergedElement(it, "ui_tree") }.toMutableList()
    val extra = mutableListOf<MergedElement>()

    for (cvElement in cvElements) {
        val matchIdx = if (uiTreeUsable) {
            tree.indices.firstOrNull { idx ->
                val treeBbox = tree[idx].element.bbox
                // Containment is how a ROW claims the switch inside it. A page-sized
                // scroll HOST contains everything on the screen by definition, so the
                // same rule let it swallow the entire CV pass — leaving only the
                // detections that fell outside it (status bar, our own overlay) and
                // making the full tier useless on exactly the screens that need it.
                // IOU is left unguarded: it is already size-sensitive, so a host never
                // matches a small box that way.
                val absorbsByContainment = mayAbsorbByContainment(treeBbox, screenHeightPx) &&
                    containmentRatio(inner = cvElement.bbox, outer = treeBbox) >= CONTAINMENT_LABEL_THRESHOLD
                iou(treeBbox, cvElement.bbox) >= IOU_OVERLAP_DROP || absorbsByContainment
            }
        } else {
            null
        }
        if (matchIdx == null) {
            extra.add(MergedElement(cvElement, "omniparser"))
            continue
        }
        val existing = tree[matchIdx].element
        if (existing.label.isBlank() && cvElement.label.isNotBlank()) {
            tree[matchIdx] = MergedElement(existing.copy(label = cvElement.label), "ui_tree")
        }
        // else: drop the CV element — the tree box already has a usable label.
    }

    return tree + extra
}

/**
 * May this tree box swallow a CV detection that sits inside it?
 *
 * Containment-absorption exists for ONE shape: a Settings-style ROW whose clickable box
 * spans the row while CV finds the small switch inside it. A row is a few percent of the
 * screen tall. So the question is "is this plausibly a row", not "is this not a page
 * host" — and those are very different questions in the gap between them.
 *
 * Measured on the Amazon home screen (1240x2772): the advert's wrapper Views are 49% and
 * 51% of screen height. Both cleared the old test, because [TreeSufficiency.isContainer]
 * only exempts boxes taller than 60%. So five boxes labelled "Sponsored Ad" / "Leave
 * feedback on sponsored advertisement" swallowed EVERY CV detection below y=131 — the
 * full tier ran, cost 683 ms, and its entire output was discarded except the status bar.
 *
 * Deliberately a SEPARATE constant from [TreeSufficiency.CONTAINER_HEIGHT_FRACTION]
 * rather than a change to it. That 0.6 is tuned against measured screens (Apple Music,
 * Swiggy) and is shared with the empty-band coverage heuristic; moving it to catch these
 * adverts would re-break what it was introduced to protect. Two different questions
 * deserve two different numbers.
 *
 * Returns true when the screen height is unknown: with no reference we cannot tell a row
 * from a host, and the previous behaviour (absorb) is the safe default for callers that
 * have no geometry.
 */
internal fun mayAbsorbByContainment(treeBbox: BBox, screenHeightPx: Int): Boolean {
    if (screenHeightPx <= 0) return true
    return (treeBbox.y2 - treeBbox.y1).toDouble() / screenHeightPx <= MAX_ABSORBING_HEIGHT_FRACTION
}

/** Fraction of [inner]'s own area that overlaps [outer] — size-invariant, unlike IOU. */
private fun containmentRatio(inner: BBox, outer: BBox): Float {
    val ix1 = max(inner.x1, outer.x1)
    val iy1 = max(inner.y1, outer.y1)
    val ix2 = min(inner.x2, outer.x2)
    val iy2 = min(inner.y2, outer.y2)
    if (ix2 <= ix1 || iy2 <= iy1) return 0f
    val inter = (ix2 - ix1) * (iy2 - iy1)
    val innerArea = (inner.x2 - inner.x1) * (inner.y2 - inner.y1)
    if (innerArea <= 0) return 0f
    return inter.toFloat() / innerArea
}

private const val CONTAINMENT_LABEL_THRESHOLD = 0.75f

/** Parsed UI-tree snapshot inputs to the tier decision. */
private data class TreeSnapshot(
    val elements: List<DetectedElement>,
    val foregroundApp: String?,
    val screenHeightPx: Int,
    val signature: ScreenSignature.Signature,
    /** ScreenActivity counter as of the tree read, so staleness is judged from then. */
    val activityAtRead: Long,
    val elapsedMs: Long,
    /**
     * A progress bar / spinner was on screen at the tree read. Last, so the positional
     * destructuring at the call site stays stable.
     */
    val loadingIndicatorPresent: Boolean,
    /**
     * The screen's readable text — the non-interactive half of the tree that
     * [UiTreeToElements] drops. Read by name, not positionally, so it can sit
     * after [loadingIndicatorPresent] without disturbing the call site.
     */
    val screenText: List<ScreenText.Block> = emptyList(),
    /**
     * Text from nodes whose rect collapsed — real content that is not on screen.
     * Produced by the walk in `UITreeExtractor`; no coordinates, by design.
     */
    val offscreenText: List<String> = emptyList(),
)

/** escalated_because value when the tree looked rich but hid most of the screen. */
private const val REASON_TREE_DEGRADED = "tree_degraded_coverage"

private fun iou(a: BBox, b: BBox): Float {
    val ix1 = max(a.x1, b.x1)
    val iy1 = max(a.y1, b.y1)
    val ix2 = min(a.x2, b.x2)
    val iy2 = min(a.y2, b.y2)
    if (ix2 <= ix1 || iy2 <= iy1) return 0f
    val inter = (ix2 - ix1) * (iy2 - iy1)
    val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
    val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
    return inter.toFloat() / (areaA + areaB - inter)
}

/**
 * Below this, the accessibility tree is not describing the screen — WebView, Canvas,
 * Maps, a game surface. Shared with `read_screen` ([ReadScreen.escalateReason]) on
 * purpose: two different thresholds would mean a screen one tool reads happily is one
 * the other refuses to trust.
 */
internal const val MIN_UI_TREE_ELEMENTS = 3
private const val IOU_OVERLAP_DROP = 0.4f

/**
 * OmniParser deadline. Past this, perceive_screen degrades to ui_tree-only with
 * `omniparser_status="timeout"` instead of blocking the agent. Bounds the tail
 * (a 10 s cold/GC spike was observed); tune down once field timings are seen.
 */
private const val CV_BUDGET_MS = 4_000L

/**
 * Tallest a tree box may be and still swallow a CV detection inside it — see
 * [mayAbsorbByContainment]. 0.25 is well above any real row (a Settings row is ~3-7% of
 * screen height, a tall media row ~15%) and well below the 49% advert wrappers that
 * caused the bug. NOT the same number as [TreeSufficiency.CONTAINER_HEIGHT_FRACTION],
 * and deliberately so.
 */
private const val MAX_ABSORBING_HEIGHT_FRACTION = 0.25

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
