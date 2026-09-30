package com.aura.mcp.cache

import com.aura.mcp.tools.ScreenSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The cheap tier's automatic escape hatch — the one escalation the model cannot make
 * for itself.
 *
 * Everything else is now the model's call (`detail="full"`) or a stateless check in
 * `TreeSufficiency`. What is left is this: a model that perceives, cannot find its
 * target, and perceives again must get MORE than it got last time, without having to
 * remember the lever.
 */
class PerceptionEscalationTest {

    private fun escalation() = PerceptionEscalation()

    @Test
    fun `re-perceiving the same screen after a cheap view upgrades to full`() {
        val e = escalation()
        e.onServed(PerceptionEscalation.Tier.CHEAP, layoutHash = 42L)
        assertTrue(
            e.repeatOfCheapView(42L),
            "the model re-looking at an unchanged screen must get MORE than last time",
        )
    }

    @Test
    fun `a screen that actually changed is not a repeat`() {
        val e = escalation()
        e.onServed(PerceptionEscalation.Tier.CHEAP, layoutHash = 42L)
        assertFalse(e.repeatOfCheapView(99L), "a different screen deserves a fresh decision")
    }

    @Test
    fun `a repeat after a FULL view does not re-run the expensive pass`() {
        // More pixels are not the missing ingredient the second time; the memo answers
        // this one with "nothing has changed" instead.
        val e = escalation()
        e.onServed(PerceptionEscalation.Tier.FULL, layoutHash = 42L)
        assertFalse(e.repeatOfCheapView(42L))
    }

    @Test
    fun `volatile text must not hide a repeat — identity is the LAYOUT lane`() {
        // The hole this closes. The content lane folds in every node's text, so any
        // ticking label — a playback position, a countdown, "2 min ago" — makes two
        // reads of the SAME screen hash differently. Under content equality the escape
        // hatch then never fires on exactly the screens most likely to need it.
        fun tree(elapsed: String) = ScreenSignature.of(
            """{"package_name":"com.apple.android.music","elements":[
                 {"className":"Button","isClickable":true,"text":"Play",
                  "bounds":{"left":0,"top":0,"right":100,"bottom":50}},
                 {"className":"TextView","text":"$elapsed",
                  "bounds":{"left":0,"top":60,"right":100,"bottom":90}}]}""",
        )
        val first = tree("0:31")
        val second = tree("0:32")
        assertNotEquals(first.content, second.content, "the ticking label does move the content lane")
        assertEquals(first.layout, second.layout, "...but it is the same screen")

        val e = escalation()
        e.onServed(PerceptionEscalation.Tier.CHEAP, layoutHash = first.layout)
        assertTrue(
            e.repeatOfCheapView(second.layout),
            "a second look at a screen whose only change was a ticking label is still a repeat",
        )
    }

    @Test
    fun `a genuine navigation is not a repeat`() {
        // The other direction: layout equality must not swallow real screen changes.
        fun tree(top: Int) = ScreenSignature.of(
            """{"package_name":"com.apple.android.music","elements":[
                 {"className":"Button","isClickable":true,"text":"Play",
                  "bounds":{"left":0,"top":$top,"right":100,"bottom":${top + 50}}}]}""",
        )
        val e = escalation()
        e.onServed(PerceptionEscalation.Tier.CHEAP, layoutHash = tree(0).layout)
        assertFalse(e.repeatOfCheapView(tree(800).layout))
    }

    @Test
    fun `a tree-blind screen never counts as a repeat`() {
        // Its hash is constant while the screen moves freely, so equality proves nothing.
        val e = escalation()
        e.onServed(PerceptionEscalation.Tier.CHEAP, layoutHash = null)
        assertFalse(e.repeatOfCheapView(null))
    }
}
