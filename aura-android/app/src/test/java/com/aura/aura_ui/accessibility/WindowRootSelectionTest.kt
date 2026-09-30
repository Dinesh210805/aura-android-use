package com.aura.aura_ui.accessibility

import com.aura.aura_ui.accessibility.WindowRootSelection.Bounds
import com.aura.aura_ui.accessibility.WindowRootSelection.WindowDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which windows the UI-tree walk reads.
 *
 * The original [UITreeExtractor.getRootNodeExcludingAura] iterated
 * `service.windows`, took the FIRST `TYPE_APPLICATION` window it happened to
 * meet, and `break`ed. Two failures follow from that:
 *
 *  1. Anything living in a window ABOVE the app — dialogs, popup menus,
 *     autocomplete dropdowns, permission sheets — was never walked. The agent
 *     perceived the screen underneath the dialog and tapped through it.
 *  2. `service.windows` has no documented ordering, so "the first
 *     TYPE_APPLICATION" can be the BACKGROUND app in split-screen or PIP.
 *
 * The fix is the same shape the Mobilerun Portal overlay uses: collect the
 * active window plus every application window layered above it, ordered.
 */
class WindowRootSelectionTest {

    private val app = WindowRootSelection.TYPE_APPLICATION
    private val system = WindowRootSelection.TYPE_SYSTEM
    private val ime = WindowRootSelection.TYPE_INPUT_METHOD

    private fun win(id: Int, layer: Int, type: Int = app, pkg: String = "com.target.app") =
        WindowDescriptor(id = id, type = type, layer = layer, packageName = pkg)

    private fun select(
        windows: List<WindowDescriptor>,
        activeWindowId: Int = -1,
        ownPackage: String = "com.aura.aura_ui.feature",
    ) = WindowRootSelection.select(windows, activeWindowId, ownPackage)

    @Test
    fun `walks the active window`() {
        val windows = listOf(win(id = 7, layer = 0))
        assertEquals(listOf(7), select(windows, activeWindowId = 7))
    }

    @Test
    fun `walks a dialog layered above the active window, dialog first`() {
        // The dialog is the surface the user is actually looking at, so it must
        // be walked first — a truncated walk must never lose the modal.
        val windows = listOf(win(id = 1, layer = 0), win(id = 2, layer = 5))
        assertEquals(listOf(2, 1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `orders several stacked windows topmost first`() {
        val windows = listOf(win(id = 1, layer = 0), win(id = 2, layer = 3), win(id = 3, layer = 9))
        assertEquals(listOf(3, 2, 1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `ignores application windows below the active one`() {
        // Split-screen / PIP background app: visible, but not what is being driven.
        val windows = listOf(win(id = 1, layer = 4), win(id = 2, layer = 1))
        assertEquals(listOf(1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `does not depend on the order windows arrive in`() {
        val ascending = listOf(win(id = 1, layer = 0), win(id = 2, layer = 5))
        val descending = ascending.reversed()
        assertEquals(
            select(ascending, activeWindowId = 1),
            select(descending, activeWindowId = 1),
        )
    }

    @Test
    fun `never walks AURA's own windows`() {
        val windows = listOf(
            win(id = 1, layer = 0),
            win(id = 2, layer = 9, pkg = "com.aura.aura_ui.feature"), // the floating overlay
        )
        assertEquals(listOf(1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `excludes AURA even when it is somehow the active window`() {
        val windows = listOf(win(id = 1, layer = 0, pkg = "com.aura.aura_ui.feature"))
        assertTrue(select(windows, activeWindowId = 1).isEmpty())
    }

    @Test
    fun `skips the keyboard — text goes through set_text, not by tapping keys`() {
        val windows = listOf(win(id = 1, layer = 0), win(id = 2, layer = 8, type = ime))
        assertEquals(listOf(1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `skips system chrome while a real app window is active`() {
        val windows = listOf(
            win(id = 1, layer = 0),
            win(id = 2, layer = 7, type = system, pkg = "com.android.systemui"),
        )
        assertEquals(listOf(1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `with no active window falls back to app and system windows topmost first`() {
        // Lock screen / notification shade: rootInActiveWindow is null, and the
        // only thing on screen belongs to systemui. Perceiving nothing is worse
        // than perceiving the shade.
        val windows = listOf(
            win(id = 1, layer = 2, type = system, pkg = "com.android.systemui"),
            win(id = 2, layer = 6),
        )
        assertEquals(listOf(2, 1), select(windows, activeWindowId = -1))
    }

    @Test
    fun `deduplicates repeated window ids`() {
        val windows = listOf(win(id = 1, layer = 0), win(id = 1, layer = 0))
        assertEquals(listOf(1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `an active id that matches no window still yields the visible app windows`() {
        // Racy read: the active window changed between the two framework calls.
        val windows = listOf(win(id = 4, layer = 3), win(id = 5, layer = 8))
        assertEquals(listOf(5, 4), select(windows, activeWindowId = 99))
    }

    // ── Occlusion ─────────────────────────────────────────────────────────────
    // Walking every stacked window is right for a dialog that floats over its
    // app, and wrong for one that covers it: the app's hundreds of nodes would
    // still be numbered and offered to the model, and tapping one does nothing.

    @Test
    fun `a fullscreen dialog hides the app underneath it`() {
        val windows = listOf(
            win(id = 1, layer = 0).copy(bounds = Bounds(0, 0, 1080, 2400)),
            win(id = 2, layer = 5).copy(bounds = Bounds(0, 0, 1080, 2400)),
        )
        assertEquals(listOf(2), select(windows, activeWindowId = 1))
    }

    @Test
    fun `a small popup does not hide the app underneath it`() {
        val windows = listOf(
            win(id = 1, layer = 0).copy(bounds = Bounds(0, 0, 1080, 2400)),
            win(id = 2, layer = 5).copy(bounds = Bounds(200, 800, 880, 1400)),
        )
        assertEquals(listOf(2, 1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `unknown bounds cannot establish occlusion — both are walked`() {
        // Bounds default to empty when the framework did not give them; a walk
        // that keeps too much is recoverable, one that drops the live surface is not.
        val windows = listOf(win(id = 1, layer = 0), win(id = 2, layer = 5))
        assertEquals(listOf(2, 1), select(windows, activeWindowId = 1))
    }

    @Test
    fun `a covering window only hides windows below it`() {
        val windows = listOf(
            win(id = 1, layer = 0).copy(bounds = Bounds(0, 0, 1080, 2400)),
            win(id = 2, layer = 5).copy(bounds = Bounds(0, 0, 1080, 2400)),
            win(id = 3, layer = 9).copy(bounds = Bounds(200, 800, 880, 1400)),
        )
        // 3 floats over 2; 2 covers 1.
        assertEquals(listOf(3, 2), select(windows, activeWindowId = 1))
    }

    @Test
    fun `no windows means nothing to walk`() {
        assertTrue(select(emptyList(), activeWindowId = 1).isEmpty())
    }

    @Test
    fun `ties on layer are broken by id so the order is deterministic`() {
        val windows = listOf(win(id = 9, layer = 4), win(id = 3, layer = 4), win(id = 1, layer = 0))
        assertEquals(listOf(3, 9, 1), select(windows, activeWindowId = 1))
    }
}
