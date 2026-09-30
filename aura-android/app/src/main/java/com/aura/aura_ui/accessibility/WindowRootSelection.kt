package com.aura.aura_ui.accessibility

/**
 * Decides WHICH accessibility windows the UI-tree walk reads, and in what order.
 *
 * Android hands an accessibility service a *list* of windows, not one tree. The
 * previous logic took the first `TYPE_APPLICATION` window it met and stopped, so:
 *
 *  - **Dialogs were invisible.** A dialog, popup menu, autocomplete dropdown or
 *    permission sheet lives in its own window layered above the app. The agent
 *    perceived the screen *underneath* it and tapped straight through.
 *  - **The wrong app could win.** `AccessibilityService.windows` has no documented
 *    ordering, so "the first `TYPE_APPLICATION`" is whatever the framework
 *    happened to list first — in split-screen or PIP that can be the background app.
 *
 * Deliberately pure (plain `Int`s, no `AccessibilityWindowInfo`) so the ordering
 * rules are unit-testable without a device — the bug was never in the tree walk,
 * it was in this decision.
 */
object WindowRootSelection {

    // Mirrors of android.view.accessibility.AccessibilityWindowInfo constants.
    // Duplicated as plain ints so this object stays free of Android types and the
    // rules can be tested on the JVM.
    const val TYPE_APPLICATION = 1
    const val TYPE_INPUT_METHOD = 2
    const val TYPE_SYSTEM = 3

    const val UNDEFINED_WINDOW_ID = -1

    /**
     * Screen rect of a window. Empty means the framework did not report one — an
     * unknown, not a zero-sized window, and it therefore neither occludes nor is
     * occluded.
     */
    data class Bounds(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
    ) {
        val isKnown: Boolean get() = right > left && bottom > top

        /** True when [other] lies entirely within this rect. */
        fun covers(other: Bounds): Boolean =
            isKnown && other.isKnown &&
                left <= other.left && top <= other.top &&
                right >= other.right && bottom >= other.bottom
    }

    data class WindowDescriptor(
        val id: Int,
        val type: Int,
        val layer: Int,
        val packageName: String,
        val bounds: Bounds = Bounds(),
    )

    /**
     * Window ids to walk, **topmost first**.
     *
     * Topmost-first is the deliberate part: when a dialog is up it IS the surface
     * the user is looking at, so it must be numbered first and must never be the
     * thing an element cap truncates away.
     *
     * Rules:
     *  - AURA's own windows are never walked (its floating overlay is not the app).
     *  - With an active window: that window, plus every *application* window layered
     *    above it. Windows below it are other apps in split-screen/PIP — visible, but
     *    not what is being driven.
     *  - With no resolvable active window (lock screen, notification shade): fall back
     *    to application and system windows, topmost first. Perceiving the shade beats
     *    perceiving nothing.
     *  - The keyboard is skipped: text is entered via `set_text` / the AURA IME, never
     *    by tapping keys, and ~30 key nodes would crowd out the real screen.
     */
    fun select(
        windows: List<WindowDescriptor>,
        activeWindowId: Int,
        ownPackage: String,
    ): List<Int> {
        val visible = windows.filter { it.packageName != ownPackage }
        val active = visible.firstOrNull {
            activeWindowId != UNDEFINED_WINDOW_ID && it.id == activeWindowId
        }

        val ordered = if (active != null) {
            val above = visible.filter {
                it.type == TYPE_APPLICATION && it.layer > active.layer && it.id != active.id
            }
            above.sortedWith(topmostFirst) + active
        } else {
            visible
                .filter { it.type == TYPE_APPLICATION || it.type == TYPE_SYSTEM }
                .sortedWith(topmostFirst)
        }

        return dropOccluded(ordered).map { it.id }.distinct()
    }

    /**
     * Drops any window completely covered by one already accepted above it.
     *
     * Walking every stacked window is right for a popup that floats over its app
     * and wrong for a dialog that covers it: the hidden app's nodes would still be
     * numbered and handed to the model, and tapping one does nothing — a dead box the
     * model has no way to recognise as dead, since it looks the same on the screenshot
     * as a live one.
     *
     * [input] must already be ordered topmost-first. Windows with unknown bounds
     * are kept: keeping too much is recoverable, dropping the live surface is not.
     */
    private fun dropOccluded(input: List<WindowDescriptor>): List<WindowDescriptor> {
        val kept = mutableListOf<WindowDescriptor>()
        for (window in input) {
            if (kept.any { it.bounds.covers(window.bounds) }) continue
            kept += window
        }
        return kept
    }

    /** Higher layer first; ties broken by id so the walk order is deterministic. */
    private val topmostFirst =
        compareByDescending<WindowDescriptor> { it.layer }.thenBy { it.id }
}
