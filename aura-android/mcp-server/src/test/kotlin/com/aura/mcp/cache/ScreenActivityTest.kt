package com.aura.mcp.cache

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Event classification. These bit values are frozen platform ABI
 * (`android.view.accessibility.AccessibilityEvent`) and are asserted here so a
 * refactor cannot quietly redefine what counts as a screen change.
 */
class ScreenActivityTest {

    private val typeViewSelected = 1 shl 2
    private val typeViewTextChanged = 1 shl 4
    private val typeWindowStateChanged = 1 shl 5
    private val typeWindowContentChanged = 1 shl 11
    private val typeViewScrolled = 1 shl 12
    private val typeWindowsChanged = 1 shl 22

    private val subtree = 1
    private val text = 1 shl 1
    private val paneAppeared = 1 shl 4
    private val paneDisappeared = 1 shl 5
    private val stateDescription = 1 shl 6
    private val enabled = 1 shl 12
    private val checked = 1 shl 13
    private val expanded = 1 shl 14

    @Test
    fun `window transitions are structural`() {
        assertEquals(ScreenActivity.Class.STRUCTURAL, ScreenActivity.classify(typeWindowStateChanged, 0))
        assertEquals(ScreenActivity.Class.STRUCTURAL, ScreenActivity.classify(typeWindowsChanged, 0))
    }

    @Test
    fun `a pane coming or going is structural`() {
        assertEquals(
            ScreenActivity.Class.STRUCTURAL,
            ScreenActivity.classify(typeWindowContentChanged, paneAppeared),
        )
        assertEquals(
            ScreenActivity.Class.STRUCTURAL,
            ScreenActivity.classify(typeWindowContentChanged, paneDisappeared),
        )
    }

    @Test
    fun `a control changing state is semantic, not noise`() {
        // These are the strongest "your tap landed" signals an automation agent gets;
        // binning them with animation noise throws away the best evidence available.
        assertEquals(ScreenActivity.Class.SEMANTIC, ScreenActivity.classify(typeWindowContentChanged, checked))
        assertEquals(ScreenActivity.Class.SEMANTIC, ScreenActivity.classify(typeWindowContentChanged, enabled))
        assertEquals(ScreenActivity.Class.SEMANTIC, ScreenActivity.classify(typeWindowContentChanged, expanded))
        assertEquals(ScreenActivity.Class.SEMANTIC, ScreenActivity.classify(typeViewSelected, 0))
    }

    @Test
    fun `SUBTREE is ambient because its javadoc gives it two meanings`() {
        // "One or more content changes occurred in the subtree rooted at the source
        // node, OR the subtree's structure changed when a node was added or removed."
        // A list row rebinding and a dialog appearing share this one bit, so it can
        // never be trusted on its own — the signature decides.
        assertEquals(ScreenActivity.Class.AMBIENT, ScreenActivity.classify(typeWindowContentChanged, subtree))
    }

    @Test
    fun `animation and typing noise is ambient`() {
        assertEquals(ScreenActivity.Class.AMBIENT, ScreenActivity.classify(typeWindowContentChanged, text))
        assertEquals(
            ScreenActivity.Class.AMBIENT,
            ScreenActivity.classify(typeWindowContentChanged, stateDescription),
        )
        assertEquals(ScreenActivity.Class.AMBIENT, ScreenActivity.classify(typeViewScrolled, 0))
        assertEquals(ScreenActivity.Class.AMBIENT, ScreenActivity.classify(typeViewTextChanged, 0))
    }

    @Test
    fun `an unknown future event type fails safe to ambient`() {
        assertEquals(ScreenActivity.Class.AMBIENT, ScreenActivity.classify(1 shl 30, 0))
    }

    @Test
    fun `structural wins when a single event carries mixed flags`() {
        assertEquals(
            ScreenActivity.Class.STRUCTURAL,
            ScreenActivity.classify(typeWindowContentChanged, subtree or text or paneAppeared),
        )
    }

    @Test
    fun `only meaningful events move the counters`() {
        val before = ScreenActivity.meaningfulCount
        ScreenActivity.record(ScreenActivity.Class.AMBIENT)
        assertEquals(before, ScreenActivity.meaningfulCount, "ambient noise must not invalidate anything")

        ScreenActivity.record(ScreenActivity.Class.STRUCTURAL)
        ScreenActivity.record(ScreenActivity.Class.SEMANTIC)
        assertEquals(before + 2, ScreenActivity.meaningfulCount)
    }
}
