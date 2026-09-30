package com.aura.aura_ui.accessibility

/**
 * Bounds the tree payload WITHOUT losing regions of the screen.
 *
 * The cap this replaces was enforced during the walk: the recursion stopped once
 * it had collected N elements. A tree walk is depth-first, so that is not a way
 * to thin a list — it keeps everything under whichever subtree came first and
 * silently drops the rest of the screen. A dense list view at the top could
 * spend the entire budget before the walk ever reached the navigation bar, and
 * the result looked exactly like an app whose tree was missing its controls.
 *
 * Applying the cap to the finished list instead makes the loss principled:
 *
 *  1. **Actionable** nodes (clickable / scrollable / editable / checkable /
 *     long-clickable) — the only things `tap` can aim at.
 *  2. **Readable** nodes — text or contentDescription; what the screen SAYS.
 *  3. **Nameless layout boxes** — real nodes, but a nameless container the model
 *     can neither act on nor read is the cheapest thing to lose.
 *
 * Within every tier document order is preserved, so bounds still read top to
 * bottom and label-borrowing by containment keeps working.
 */
object ElementBudget {

    /**
     * Must agree with `UiTreeToElements.isInteractive` on the consumer side —
     * including the ACTION-LIST arm. The `isClickable` flag is what the developer
     * set; the action list is what the framework will actually dispatch, and they
     * disagree in both directions on real screens. Ranking on the flag alone drops
     * nodes that the consumer would then have shipped as tappable som_ids.
     */
    private fun isActionable(e: UIElementData): Boolean =
        e.isClickable || e.isScrollable || e.isEditable || e.isCheckable ||
            e.isLongClickable || e.actions.contains("click")

    private fun tier(e: UIElementData): Int = when {
        isActionable(e) -> 0
        !e.text.isNullOrBlank() || !e.contentDescription.isNullOrBlank() -> 1
        else -> 2
    }

    /**
     * At most [limit] elements, dropping the least useful tier first and keeping
     * document order among the survivors.
     */
    fun trim(elements: List<UIElementData>, limit: Int): List<UIElementData> {
        if (elements.size <= limit) return elements

        val keep = HashSet<Int>(limit)
        var budget = limit
        for (t in 0..2) {
            if (budget == 0) break
            for ((index, e) in elements.withIndex()) {
                if (budget == 0) break
                if (tier(e) != t) continue
                keep.add(index)
                budget--
            }
        }
        return elements.filterIndexed { index, _ -> index in keep }
    }

    /** True when [trim] would actually drop something — the honesty flag's input. */
    fun wasTrimmed(elements: List<UIElementData>, limit: Int): Boolean = elements.size > limit
}
