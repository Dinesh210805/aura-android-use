package com.aura.aura_ui.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives when a screen carries more nodes than the payload can hold.
 *
 * The old cap stopped the tree WALK at 400 nodes. Because the walk is
 * depth-first, that is not "thin the list" — it is "keep everything under the
 * first subtree and drop whole regions of the screen." A dense list view at the
 * top could consume the entire budget before the walk ever reached the nav bar.
 *
 * The cap has to be applied to the finished list, and it has to drop the least
 * useful things first: a node the agent can ACT on outranks a node it can only
 * read, and a node it can read outranks a nameless layout box.
 */
class ElementBudgetTest {

    private fun node(
        id: Int,
        clickable: Boolean = false,
        text: String? = null,
    ) = UIElementData(
        text = text,
        contentDescription = null,
        bounds = BoundsData(0, id * 10, 100, id * 10 + 8, 50, id * 10 + 4, 100, 8),
        className = "android.widget.TextView",
        isClickable = clickable,
        isScrollable = false,
        isEditable = false,
        isEnabled = true,
        isFocused = false,
        actions = emptyList(),
        packageName = "com.example",
        viewId = "id/n$id",
    )

    @Test
    fun `a list within budget is returned untouched`() {
        val elements = (1..10).map { node(it) }
        assertEquals(elements, ElementBudget.trim(elements, limit = 400))
    }

    @Test
    fun `an over-budget list is cut to the limit`() {
        val elements = (1..500).map { node(it, text = "row $it") }
        assertEquals(400, ElementBudget.trim(elements, limit = 400).size)
    }

    @Test
    fun `actionable nodes survive a cut that fills up on layout boxes`() {
        // The failure this pins: 400 nameless containers at the top of the tree
        // used to consume the whole budget, so the buttons below never shipped.
        val filler = (1..400).map { node(it) }
        val buttons = (401..410).map { node(it, clickable = true, text = "Button $it") }

        val kept = ElementBudget.trim(filler + buttons, limit = 400)

        assertTrue(
            "every actionable node must survive",
            buttons.all { it in kept },
        )
    }

    @Test
    fun `a node actionable only via its action list is treated as actionable`() {
        // UiTreeToElements.isInteractive takes the UNION of the isClickable flag
        // and the dispatchable action list, because they disagree in both
        // directions on real screens. Ranking here on the flag alone would trim
        // away a node that then WOULD have shipped as a som_id.
        // The node carries NO text and NO isClickable flag — only a dispatchable
        // click action — so it must outrank 400 labeled-but-inert text nodes.
        val labeled = (1..400).map { node(it, text = "row $it") }
        val actionOnly = node(401).copy(actions = listOf("click"))

        val kept = ElementBudget.trim(labeled + actionOnly, limit = 400)

        assertTrue("action-list-only node must survive", actionOnly in kept)
    }

    @Test
    fun `readable nodes outrank nameless layout boxes`() {
        val nameless = (1..300).map { node(it) }
        val labeled = (301..320).map { node(it, text = "Price $it") }

        val kept = ElementBudget.trim(nameless + labeled, limit = 100)

        assertTrue("text is what the model reads", labeled.all { it in kept })
    }

    @Test
    fun `actionable nodes outrank readable ones when both cannot fit`() {
        val labeled = (1..100).map { node(it, text = "Label $it") }
        val buttons = (101..150).map { node(it, clickable = true, text = "Button $it") }

        val kept = ElementBudget.trim(labeled + buttons, limit = 50)

        assertEquals(buttons.toSet(), kept.toSet())
    }

    @Test
    fun `a cut that keeps only actionable nodes still respects the limit`() {
        val buttons = (1..500).map { node(it, clickable = true, text = "Button $it") }
        assertEquals(50, ElementBudget.trim(buttons, limit = 50).size)
    }

    @Test
    fun `document order is preserved so bounds still read top to bottom`() {
        val elements = (1..500).map { node(it, text = "row $it") }
        val kept = ElementBudget.trim(elements, limit = 100)
        assertEquals(kept.sortedBy { it.bounds.top }, kept)
    }

    @Test
    fun `reports whether anything was actually dropped`() {
        val small = (1..10).map { node(it) }
        assertFalse(ElementBudget.wasTrimmed(small, limit = 400))
        assertTrue(ElementBudget.wasTrimmed((1..500).map { node(it) }, limit = 400))
    }

    @Test
    fun `an empty list is not a truncation`() {
        assertEquals(emptyList<UIElementData>(), ElementBudget.trim(emptyList(), limit = 400))
        assertFalse(ElementBudget.wasTrimmed(emptyList(), limit = 400))
    }
}
