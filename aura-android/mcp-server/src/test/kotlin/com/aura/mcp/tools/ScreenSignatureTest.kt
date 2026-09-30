package com.aura.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The signature is the authority on screen identity, so its failure modes are the
 * ones that matter: it must not churn on animation jitter (or every frame reads as a
 * new screen), it must not miss a real change (or the agent taps a dead screen), and
 * it must ADMIT when the tree cannot describe the screen at all.
 */
class ScreenSignatureTest {

    private fun tree(
        pkg: String = "com.example",
        vararg nodes: String,
    ) = """{"package_name":"$pkg","elements":[${nodes.joinToString(",")}]}"""

    private fun node(
        text: String = "Play",
        left: Int = 100,
        top: Int = 200,
        right: Int = 300,
        bottom: Int = 400,
        clickable: Boolean = true,
        checked: Boolean? = null,
        className: String = "android.widget.Button",
    ): String = buildString {
        append("""{"className":"$className","text":"$text","isClickable":$clickable""")
        append(""","bounds":{"left":$left,"top":$top,"right":$right,"bottom":$bottom}""")
        if (checked != null) append(""","isCheckable":true,"isChecked":$checked""")
        append("}")
    }

    @Test
    fun `the same screen hashes identically`() {
        val a = ScreenSignature.of(tree(nodes = arrayOf(node())))
        val b = ScreenSignature.of(tree(nodes = arrayOf(node())))
        assertEquals(a.content, b.content)
        assertEquals(a.layout, b.layout)
        assertFalse(a.treeBlind)
        assertTrue(a.sameContentAs(b))
    }

    @Test
    fun `sub-quantum movement does not change the signature`() {
        // A view settling into place by a few pixels must not read as a new screen,
        // or every animation frame invalidates the perception cache.
        val a = ScreenSignature.of(tree(nodes = arrayOf(node(left = 100, top = 200))))
        val b = ScreenSignature.of(tree(nodes = arrayOf(node(left = 103, top = 205))))
        assertEquals(a.content, b.content, "movement inside one ${ScreenSignature.BOUNDS_QUANTUM}px cell is noise")
    }

    @Test
    fun `real movement does change the signature`() {
        val a = ScreenSignature.of(tree(nodes = arrayOf(node(top = 200))))
        val b = ScreenSignature.of(tree(nodes = arrayOf(node(top = 600))))
        assertNotEquals(a.content, b.content)
        assertNotEquals(a.layout, b.layout)
    }

    @Test
    fun `a text change moves content but not layout`() {
        // This split is the whole point: "same screen, new data" must stay one screen
        // for trust and CV reuse, while still counting as a change for the agent.
        val a = ScreenSignature.of(tree(nodes = arrayOf(node(text = "Play"))))
        val b = ScreenSignature.of(tree(nodes = arrayOf(node(text = "Pause"))))
        assertEquals(a.layout, b.layout, "the screen is structurally the same")
        assertNotEquals(a.content, b.content, "but something visibly changed")
        assertTrue(a.sameLayoutAs(b))
        assertFalse(a.sameContentAs(b))
    }

    @Test
    fun `a toggle flipping is a content change`() {
        val off = ScreenSignature.of(tree(nodes = arrayOf(node(checked = false))))
        val on = ScreenSignature.of(tree(nodes = arrayOf(node(checked = true))))
        assertNotEquals(off.content, on.content, "Wi-Fi going on must not read as 'nothing happened'")
    }

    @Test
    fun `becoming clickable is a layout change`() {
        val disabled = ScreenSignature.of(tree(nodes = arrayOf(node(clickable = false))))
        val enabled = ScreenSignature.of(tree(nodes = arrayOf(node(clickable = true))))
        assertNotEquals(disabled.layout, enabled.layout, "a button becoming actionable restructures the screen")
    }

    @Test
    fun `the same layout in a different app is a different screen`() {
        val a = ScreenSignature.of(tree(pkg = "com.a", nodes = arrayOf(node())))
        val b = ScreenSignature.of(tree(pkg = "com.b", nodes = arrayOf(node())))
        assertFalse(a.sameContentAs(b))
        assertFalse(a.sameLayoutAs(b))
    }

    @Test
    fun `node order is significant`() {
        val a = ScreenSignature.of(tree(nodes = arrayOf(node(text = "A"), node(text = "B", top = 500))))
        val b = ScreenSignature.of(tree(nodes = arrayOf(node(text = "B"), node(text = "A", top = 500))))
        assertNotEquals(a.content, b.content, "two labels swapping places is a real change")
    }

    @Test
    fun `the resource id is part of the identity`() {
        // Guards the exact key the producer emits: UITreeExtractor writes `viewId`
        // (holding AccessibilityNodeInfo.viewIdResourceName). Reading the wrong name
        // silently drops the single most stable identity field Android offers — no
        // crash, just a weaker signature, which is the kind of bug that hides forever.
        val json = { id: String ->
            """{"package_name":"com.x","elements":[
                 {"className":"android.widget.Button","text":"Go","isClickable":true,"viewId":"$id",
                  "bounds":{"left":0,"top":0,"right":100,"bottom":50}}]}"""
        }
        assertNotEquals(
            ScreenSignature.of(json("com.x:id/search")).layout,
            ScreenSignature.of(json("com.x:id/profile")).layout,
            "two different controls at the same spot must not hash alike",
        )
    }

    @Test
    fun `a validation-failed tree is blind and never compares equal`() {
        val blind = ScreenSignature.of(
            """{"package_name":"com.game","validation_failed":true,"requires_vision":true}""",
        )
        assertTrue(blind.treeBlind)
        assertEquals("com.game", blind.packageName)
        assertFalse(
            blind.sameContentAs(blind),
            "a blind tree stays constant while the screen moves — it must never assert stillness",
        )
    }

    @Test
    fun `malformed or empty input degrades to blind instead of throwing`() {
        assertTrue(ScreenSignature.of("not json at all").treeBlind)
        assertTrue(ScreenSignature.of("{}").treeBlind)
        assertTrue(ScreenSignature.of("""{"package_name":"com.x","elements":[]}""").treeBlind)
    }

    @Test
    fun `degenerate bounds are ignored`() {
        // Zero-area nodes are dropped by every downstream consumer; including them
        // would let an off-screen view flap a signature it can never be tapped from.
        val withJunk = ScreenSignature.of(
            tree(nodes = arrayOf(node(), node(text = "ghost", left = 5, right = 5, top = 5, bottom = 5))),
        )
        val clean = ScreenSignature.of(tree(nodes = arrayOf(node())))
        assertEquals(clean.content, withJunk.content)
        assertEquals(1, withJunk.nodeCount)
    }
}
