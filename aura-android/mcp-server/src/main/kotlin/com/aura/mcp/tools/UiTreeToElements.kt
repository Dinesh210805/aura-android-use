package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 10 — parse the opaque UI-tree JSON payload from [UiTreeBridge]
 * into [DetectedElement]s suitable for SoM annotation.
 *
 * Why this lives in `:mcp-server` and not in `:app`: the `:app` side
 * already produces the JSON in [com.aura.aura_ui.accessibility.UITreeExtractor];
 * the parsing back into structured elements is consumer-side concern. By
 * keeping it here we can swap detection sources (UI tree vs OmniParser)
 * inside [registerPerceiveScreenTool] without bouncing through the
 * bridge layer.
 *
 * Selection policy: **geometry only**. Every node with a real on-screen rect is
 * emitted, interactive or not; role is carried by box COLOUR in the annotated
 * image rather than by admission to the list.
 *
 * This replaced an interactivity filter. The filter looked conservative and was
 * lossy: "the app did not set isClickable" is not evidence that a thing cannot
 * be tapped, and on the measured Amazon home screen it removed 43 distinct
 * on-screen regions. Because the payload is now short-form, an extra element
 * costs ~13 characters — so the default flipped from "prove it deserves to be
 * sent" to "send it unless it is actively harmful".
 *
 * Bounds invariant: every returned bbox is guaranteed non-empty
 * (x2 > x1 && y2 > y1). Empty or degenerate boxes from the accessibility
 * tree are filtered out — they happen when an element is partially
 * off-screen or has zero pixels rendered.
 */
internal object UiTreeToElements {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse the JSON snapshot. Returns an empty list when the tree is
     * absent, marked `validation_failed`, or has fewer than [minElements]
     * interactive nodes — the caller treats that as "fall back to
     * OmniParser CV."
     */
    fun extract(payloadJson: String, minElements: Int = 3): List<DetectedElement> {
        val root: JsonObject = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: return emptyList()

        // The validator at :app side marks WebView / Canvas screens with
        // validation_failed=true plus requires_vision=true. Respect that
        // signal — those screens are exactly what OmniParser is for.
        val validationFailed =
            root["validation_failed"]?.jsonPrimitive?.booleanOrNull == true
        if (validationFailed) return emptyList()

        val rawElements: JsonArray =
            root["elements"]?.jsonArray ?: return emptyList()

        // Stage 1: every element with valid bounds, interactive or not — the
        // candidate pool for label recovery below. [UITreeExtractor] on the
        // :app side already flattened the accessibility tree into this list
        // with no parent/child links, so bounds containment is the only
        // available proxy for "belongs to that element" — and a reliable one,
        // since Android layout containment strongly correlates with view-tree
        // containment.
        val candidates = rawElements.mapNotNull { raw -> (raw as? JsonObject)?.let(::toCandidate) }

        val out = mutableListOf<DetectedElement>()
        var nextId = 1
        for (raw in rawElements) {
            val obj = raw as? JsonObject ?: continue

            // NO interactivity filter. "Not flagged clickable" is not evidence of "not
            // tappable" — apps routinely omit the flag on real targets, and this
            // project's own TreeSufficiency KDoc records a measured case ("the search
            // bar in the empty band IS tappable, the app just doesn't expose it as
            // clickable"). Measured on the Amazon home screen, the filter discarded 43
            // distinct on-screen regions, 40 of them a plausible size for a control.
            //
            // Everything with a real rect is now emitted and annotated; role is conveyed
            // by BOX COLOUR, which costs no tokens, and the model decides by looking.
            // The invariant this creates matters: what we DRAW and what we SEND are the
            // same set, so a number on the image always resolves to an entry here.
            val interactive = isInteractive(obj)
            val scrollable = obj["isScrollable"]?.jsonPrimitive?.booleanOrNull == true
            val editable = obj["isEditable"]?.jsonPrimitive?.booleanOrNull == true
            val checkable = obj["isCheckable"]?.jsonPrimitive?.booleanOrNull == true
            val longClickable = obj["isLongClickable"]?.jsonPrimitive?.booleanOrNull == true
            val focused = obj["isFocused"]?.jsonPrimitive?.booleanOrNull == true

            // A disabled node can still report isClickable=true (greyed-out
            // placeholders do). Keep it — but tagged, so the agent can tell
            // "unavailable" from "tappable" instead of burning a turn on it.
            // Absent field means enabled: older payloads omit it entirely.
            val enabled = obj["isEnabled"]?.jsonPrimitive?.booleanOrNull != false

            val bboxObj = obj["bounds"]?.jsonObject ?: continue
            val left = bboxObj["left"]?.jsonPrimitive?.intOrNull ?: continue
            val top = bboxObj["top"]?.jsonPrimitive?.intOrNull ?: continue
            val right = bboxObj["right"]?.jsonPrimitive?.intOrNull ?: continue
            val bottom = bboxObj["bottom"]?.jsonPrimitive?.intOrNull ?: continue
            if (right <= left || bottom <= top) continue

            val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val contentDescription =
                obj["contentDescription"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val className = obj["className"]?.jsonPrimitive?.contentOrNull.orEmpty()

            // Label preference: developer-authored text first, then
            // contentDescription (often more accurate for icon-only buttons).
            // If the node carries neither — a very common pattern where the
            // real tap target is a bare row/container and the informative text
            // belongs to a non-clickable child (e.g. a Settings toggle row) —
            // borrow one from a contained descendant instead of shipping the
            // model a nameless box for the one element the task might need.
            // Caller is still responsible for not blindly trusting labels —
            // that's a contract rule regardless of where the label came from.
            val ownLabel = when {
                text.isNotBlank() -> text
                contentDescription.isNotBlank() -> contentDescription
                else -> ""
            }
            // text -> contentDescription -> borrowed from a descendant -> viewId -> class.
            //
            // The last two rungs are new, and they are why no box ships nameless any
            // more: measured on the Amazon home screen, 22 of 49 tappable boxes had no
            // name at all, and this chain takes that to 0. `viewId` was already in the
            // tree payload on 95 of 152 nodes and was simply being discarded here.
            //
            // Some fallback names are poor ("View", "WebView") and some are genuinely
            // descriptive ("glow_subnav_ingress", "tiles_nav_scrollable_container"). A
            // weak name still beats an empty one: the model is choosing between numbered
            // boxes, and a box with no name is the one it cannot reason about at all.
            val viewId = obj["viewId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val label = ownLabel
                .ifBlank { borrowedLabel(LabelCandidate(left, top, right, bottom, "", ""), candidates) }
                .ifBlank { viewId.substringAfterLast('/') }
                .ifBlank { className.substringAfterLast('.') }

            val kind = when {
                editable -> "editable"
                checkable -> "checkable"
                scrollable -> "scrollable"
                interactive -> "clickable"
                else -> "passive"
            }

            out.add(
                DetectedElement(
                    somId = nextId++,
                    bbox = BBox(x1 = left, y1 = top, x2 = right, y2 = bottom),
                    elementType = if (className.isNotBlank()) {
                        className.substringAfterLast('.').ifBlank { kind }
                    } else {
                        kind
                    },
                    label = label,
                    confidence = 1.0f, // UI tree is factual, not probabilistic.
                    // null (not false) for non-checkable nodes — "off" and
                    // "has no on/off state" must stay distinguishable.
                    checked = if (checkable) {
                        obj["isChecked"]?.jsonPrimitive?.booleanOrNull == true
                    } else {
                        null
                    },
                    enabled = enabled,
                    interactive = interactive,
                    editable = editable,
                    scrollable = scrollable,
                    longClickable = longClickable,
                    focused = focused,
                ),
            )
        }

        return if (out.size < minElements) emptyList() else out
    }

    /**
     * The single definition of "the agent can act on this node", shared with
     * [ScreenText] so the two channels PARTITION the tree instead of
     * overlapping or, worse, both dropping the same node.
     *
     * `isClickable` is a flag the developer set; the action list is what the
     * framework will actually dispatch. They disagree in BOTH directions on
     * real screens, so take the union and let `isEnabled` do the rejecting
     * downstream.
     */
    fun isInteractive(obj: JsonObject): Boolean {
        fun flag(name: String) = obj[name]?.jsonPrimitive?.booleanOrNull == true
        if (flag("isClickable") || flag("isScrollable") || flag("isEditable") ||
            flag("isCheckable") || flag("isLongClickable")
        ) {
            return true
        }
        return obj["actions"]?.jsonArray.orEmpty().any {
            it.jsonPrimitive.contentOrNull == "click"
        }
    }

    internal fun toCandidate(obj: JsonObject): LabelCandidate? {
        val bboxObj = obj["bounds"]?.jsonObject ?: return null
        val left = bboxObj["left"]?.jsonPrimitive?.intOrNull ?: return null
        val top = bboxObj["top"]?.jsonPrimitive?.intOrNull ?: return null
        val right = bboxObj["right"]?.jsonPrimitive?.intOrNull ?: return null
        val bottom = bboxObj["bottom"]?.jsonPrimitive?.intOrNull ?: return null
        if (right <= left || bottom <= top) return null
        return LabelCandidate(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            contentDescription = obj["contentDescription"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}

/** Minimal geometry + text needed to search for a borrowable label. Pure, no JSON. */
internal data class LabelCandidate(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val contentDescription: String,
)

/**
 * Bounded label recovery for a node whose own text + contentDescription are
 * blank. Joins the first few distinct texts (document order — top-to-bottom,
 * matching how the accessibility tree was walked) of [candidates] STRICTLY inside
 * [target]'s bounds, falling back to the first non-blank contentDescription.
 * Returns "" when nothing qualifies — an honest empty label beats a guessed one.
 *
 * Several texts, not the first one: a search-suggestion row holds "Salem" and
 * "Tamil Nadu" in two child views, and borrowing only "Salem" made the right row
 * read exactly like the search box and 70 other elements, while the wrong row
 * ("Salem New Bus Stand") was the one with a unique name (F15, 2026-09-24).
 *
 * Pure function: no JSON, no Android types — independently unit-testable
 * against the exact bounds captured off a real device (see
 * [UiTreeToElementsTest]'s Wi-Fi-toggle-row regression case).
 */
internal fun borrowedLabel(target: LabelCandidate, candidates: List<LabelCandidate>): String {
    val texts = borrowedTexts(target, candidates)
    if (texts.isNotEmpty()) return texts.joinToString(" · ") { it.text.trim() }.take(MAX_BORROWED_CHARS)
    return borrowedLabelFrom(target, candidates)?.first?.contentDescription.orEmpty()
}

/** The texts [borrowedLabel] joins: contained, non-blank, distinct, at most [MAX_BORROWED_TEXTS]. */
internal fun borrowedTexts(target: LabelCandidate, candidates: List<LabelCandidate>): List<LabelCandidate> =
    candidates.asSequence()
        .filter { it.text.isNotBlank() && target.contains(it) }
        .distinctBy { it.text.trim() }
        .take(MAX_BORROWED_TEXTS)
        .toList()

private fun LabelCandidate.contains(c: LabelCandidate): Boolean =
    c.left >= left && c.top >= top && c.right <= right && c.bottom <= bottom

/** A row's name, a subtitle, one more: enough to tell rows apart without a paragraph. */
internal const val MAX_BORROWED_TEXTS = 3
internal const val MAX_BORROWED_CHARS = 80

/**
 * Same selection as [borrowedLabel] but returns WHICH candidate was borrowed
 * from, and whether its `text` or its `contentDescription` was taken.
 *
 * [ScreenText] needs the identity, not just the string: a text node whose
 * content has already been lifted into a containing element's label must not
 * be repeated in the screen-text channel, and matching on the string alone
 * would also delete legitimately identical text elsewhere on screen.
 */
internal fun borrowedLabelFrom(
    target: LabelCandidate,
    candidates: List<LabelCandidate>,
): Pair<LabelCandidate, Boolean>? {
    fun contains(c: LabelCandidate): Boolean =
        c.left >= target.left && c.top >= target.top && c.right <= target.right && c.bottom <= target.bottom

    candidates.firstOrNull { it.text.isNotBlank() && contains(it) }?.let { return it to true }
    candidates.firstOrNull { it.contentDescription.isNotBlank() && contains(it) }?.let { return it to false }
    return null
}
