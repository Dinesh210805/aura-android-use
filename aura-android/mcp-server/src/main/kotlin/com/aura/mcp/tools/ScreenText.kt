package com.aura.mcp.tools

import com.aura.mcp.bridge.BBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The screen's READABLE content — the half of the accessibility tree that
 * [UiTreeToElements] deliberately throws away.
 *
 * `UiTreeToElements` keeps only interactive nodes, which is right for som_id
 * targets: a heading is not something the model can tap. But it was the ONLY
 * path from tree to agent, so every heading, price, status line, error message
 * and list subtitle was silently deleted before the model ever saw it. The
 * visible symptom was that OmniParser's OCR appeared to "find things the UI
 * tree missed" — it was re-deriving from pixels, at real latency and with
 * confidence scores, text the framework had already handed us for free.
 *
 * The two objects PARTITION the tree on [UiTreeToElements.isInteractive]:
 * anything actionable ships as a numbered element, anything readable ships
 * here. Nothing is dropped by both.
 */
internal object ScreenText {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Upper bound on blocks returned. Text is cheap per item but a long list
     * view can carry hundreds; this keeps the payload bounded without the
     * spatial bias of a depth-first cut (blocks are sorted into reading order
     * BEFORE the cap, so what survives is the top of the screen, which is what
     * a person would read first).
     */
    const val DEFAULT_LIMIT = 60

    data class Block(val text: String, val bbox: BBox)

    /**
     * Text the walk saw on nodes whose rect had collapsed — real content that is NOT on
     * screen right now, typically the rest of a horizontal carousel.
     *
     * Distinct from [extract] in the one way that matters: these carry **no
     * coordinates**. Android reports a node's rect clipped to what is visible, so a
     * card scrolled off to the right comes back as a zero-area sliver at the viewport
     * edge — the words are real, the position is not. Measured on the Amazon home
     * screen: 170 such nodes holding 138 distinct strings, including the entire visible
     * offer the agent was unable to read.
     *
     * Emitted as bare strings on purpose. Giving them a plausible-looking position —
     * the carousel's own rect, say — would place off-screen text where the user can see
     * it, and a confident wrong answer is worse than an honest "this exists, somewhere
     * over there".
     */
    fun offscreen(payloadJson: String): List<String> {
        val root: JsonObject = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: return emptyList()
        return root["offscreen_text"]?.jsonArray
            .orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    }

    fun extract(payloadJson: String, limit: Int = DEFAULT_LIMIT): List<Block> {
        val root: JsonObject = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: return emptyList()

        // WebView / Canvas screens the :app-side validator flagged as
        // requires_vision. Their tree text is garbage or absent; OmniParser's
        // OCR is the honest source there.
        if (root["validation_failed"]?.jsonPrimitive?.booleanOrNull == true) return emptyList()

        val rawElements: JsonArray = root["elements"]?.jsonArray ?: return emptyList()
        val objects = rawElements.mapNotNull { it as? JsonObject }

        val borrowed = borrowedCandidates(objects)

        val blocks = mutableListOf<Block>()
        val seen = mutableSetOf<String>()
        for (obj in objects) {
            if (UiTreeToElements.isInteractive(obj)) continue
            val candidate = UiTreeToElements.toCandidate(obj) ?: continue
            val text = candidate.text.ifBlank { candidate.contentDescription }
            if (text.isBlank()) continue
            // Already lifted into a containing element's label — repeating it
            // here is duplication the model pays for in tokens and confusion.
            if (borrowed.contains(candidate.identity())) continue
            if (!seen.add("${candidate.left},${candidate.top},${candidate.right},${candidate.bottom}|$text")) {
                continue
            }
            blocks.add(
                Block(
                    text = text.trim(),
                    bbox = BBox(
                        x1 = candidate.left,
                        y1 = candidate.top,
                        x2 = candidate.right,
                        y2 = candidate.bottom,
                    ),
                ),
            )
        }

        // Reading order — top to bottom, then left to right — so the cap below
        // truncates the bottom of the screen rather than an arbitrary subtree.
        blocks.sortWith(compareBy({ it.bbox.y1 }, { it.bbox.x1 }))
        return if (blocks.size > limit) blocks.take(limit) else blocks
    }

    /**
     * Payload shape. Centers rather than raw bounds, matching how elements are
     * reported — the model uses them to say WHERE a piece of text sits relative
     * to the numbered boxes, not to tap it. Text is not tappable; there is
     * deliberately no som_id here.
     */
    fun toJson(blocks: List<Block>): JsonArray = buildJsonArray {
        for (b in blocks) {
            add(
                buildJsonObject {
                    put("text", b.text)
                    put("center_x", (b.bbox.x1 + b.bbox.x2) / 2)
                    put("center_y", (b.bbox.y1 + b.bbox.y2) / 2)
                },
            )
        }
    }

    /**
     * Identities of the text nodes that [UiTreeToElements] will lift into some
     * containing interactive element's label. Recomputed with the SAME
     * selection function rather than string-matched, so identical text
     * elsewhere on screen is not collateral damage.
     */
    private fun borrowedCandidates(objects: List<JsonObject>): Set<String> {
        val candidates = objects.mapNotNull(UiTreeToElements::toCandidate)
        val borrowed = mutableSetOf<String>()
        for (obj in objects) {
            if (!UiTreeToElements.isInteractive(obj)) continue
            val self = UiTreeToElements.toCandidate(obj) ?: continue
            // Only nodes with no label of their own borrow one.
            if (self.text.isNotBlank() || self.contentDescription.isNotBlank()) continue
            val texts = borrowedTexts(self, candidates)
            if (texts.isNotEmpty()) {
                texts.forEach { borrowed.add(it.identity()) }
            } else {
                borrowedLabelFrom(self, candidates)?.let { (chosen, _) -> borrowed.add(chosen.identity()) }
            }
        }
        return borrowed
    }

    private fun LabelCandidate.identity(): String =
        "$left,$top,$right,$bottom|$text|$contentDescription"
}
