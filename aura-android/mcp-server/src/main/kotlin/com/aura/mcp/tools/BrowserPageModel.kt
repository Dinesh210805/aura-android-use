package com.aura.mcp.tools

import com.aura.mcp.bridge.BrowserPage
import com.aura.mcp.bridge.looksLikeLoginWall
import com.aura.mcp.bridge.RawElement

/**
 * Pure projection from an engine-scraped [BrowserPage] to the budgeted, opaque-handle
 * view the model is allowed to see.
 *
 * Two invariants, both load-bearing:
 *
 *  1. **Selectors stay server-side.** The model addresses elements as `el_id: 7`, the
 *     same way `perceive_screen` hides pixel bounds behind `som_id`. Leak a CSS
 *     selector and the model starts inventing them, which fails silently on the next
 *     page that renders slightly differently.
 *  2. **Everything is budgeted.** A news homepage carries thousands of nodes; the
 *     on-device agent runs against a 30k TPM ceiling. Truncation is explicit and
 *     reported, never silent — a silently trimmed page reads to the model as a
 *     complete one, which is how agents conclude "the link isn't there".
 */
internal object BrowserPageModel {

    /** Roughly a page of prose — enough to answer from, small enough to spend freely. */
    const val DEFAULT_MAX_TEXT_CHARS: Int = 4_000

    /** Interactive elements. Real pages rarely need more than this to make progress. */
    const val DEFAULT_MAX_ELEMENTS: Int = 60

    private val WHITESPACE = Regex("\\s+")

    /** Roles that only ever appear behind an authentication wall. */
    fun snapshot(
        page: BrowserPage,
        generation: Int,
        maxTextChars: Int = DEFAULT_MAX_TEXT_CHARS,
        maxElements: Int = DEFAULT_MAX_ELEMENTS,
    ): PageSnapshot {
        val normalisedText = normalise(page.text)
        val budgetedText = truncateOnWordBoundary(normalisedText, maxTextChars)

        val kept = page.elements.take(maxElements)
        val numbered = kept.mapIndexed { index, raw -> raw.toNumbered(elId = index + 1) }

        return PageSnapshot(
            generation = generation,
            url = page.url,
            title = normalise(page.title),
            text = budgetedText,
            textTruncated = budgetedText.length < normalisedText.length,
            elements = numbered,
            elementsTruncated = page.elements.size > kept.size,
            totalElements = page.elements.size,
            handles = numbered.indices.associate { i -> (i + 1) to kept[i].selector },
            // Checked across ALL elements, not just the ones that survived the budget:
            // a password field pushed past the cap is still a login wall.
            looksLikeLoginWall = page.looksLikeLoginWall,
        )
    }

    private fun RawElement.toNumbered(elId: Int): NumberedElement {
        val cleaned = normalise(label)
        return NumberedElement(
            elId = elId,
            role = role,
            // Icon-only controls are often the only way forward. Synthesising a label
            // keeps them addressable; dropping them would leave a hole the model
            // cannot reason about.
            label = cleaned.ifBlank { "(unlabeled $role)" },
            value = value,
            disabled = disabled,
        )
    }

    private fun normalise(raw: String): String = raw.replace(WHITESPACE, " ").trim()

    /**
     * Cut to [max] characters without splitting a word. A half-word ending ("…the
     * chec") reads to a model as a real token and invites bad guesses.
     */
    private fun truncateOnWordBoundary(text: String, max: Int): String {
        if (text.length <= max) return text
        val hardCut = text.substring(0, max)
        val lastSpace = hardCut.lastIndexOf(' ')
        return if (lastSpace <= 0) hardCut else hardCut.substring(0, lastSpace).trimEnd()
    }
}

/** The budgeted, model-facing view of one page state. */
internal data class PageSnapshot(
    val generation: Int,
    val url: String,
    val title: String,
    val text: String,
    val textTruncated: Boolean,
    val elements: List<NumberedElement>,
    val elementsTruncated: Boolean,
    val totalElements: Int,
    /** `el_id` → engine-native selector. Never serialised into a tool result. */
    val handles: Map<Int, String>,
    val looksLikeLoginWall: Boolean,
)

internal data class NumberedElement(
    val elId: Int,
    val role: String,
    val label: String,
    val value: String?,
    val disabled: Boolean,
)
