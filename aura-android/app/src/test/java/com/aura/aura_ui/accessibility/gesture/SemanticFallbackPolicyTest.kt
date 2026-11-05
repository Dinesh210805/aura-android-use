package com.aura.aura_ui.accessibility.gesture

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback decision, pinned. Getting this wrong is expensive in both directions:
 * too eager and every working app receives a second, duplicate click; too shy and
 * gesture-filtering apps (Swiggy) stay broken while reporting success.
 */
class SemanticFallbackPolicyTest {

    @Test
    fun `falls back when the app did not react and a node is available`() {
        // The Swiggy case: OS played the gesture, target discarded it, node is clickable.
        assertTrue(
            SemanticFallbackPolicy.shouldFallBack(
                uiMutatedAfterDispatch = false,
                hasClickableNode = true,
            ),
        )
    }

    @Test
    fun `does not fall back when the app reacted`() {
        // The Settings case. Falling back here would double-fire the click.
        assertFalse(
            SemanticFallbackPolicy.shouldFallBack(
                uiMutatedAfterDispatch = true,
                hasClickableNode = true,
            ),
        )
    }

    @Test
    fun `does not fall back when there is no node to click`() {
        // Tapping blank canvas, a SurfaceView, or a Flutter/RN surface with no
        // clickable node exposed — there is nothing to fall back to.
        assertFalse(
            SemanticFallbackPolicy.shouldFallBack(
                uiMutatedAfterDispatch = false,
                hasClickableNode = false,
            ),
        )
    }

    @Test
    fun `an input echo is not a reaction`() {
        // The Files app (DocumentsUI) emits TYPE_VIEW_CLICKED for an injected tap and then
        // ignores it. Counting that echo as a reaction skipped the fallback on every tap.
        listOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
        ).forEach { assertFalse("type $it", SemanticFallbackPolicy.isReaction(it)) }
    }

    @Test
    fun `a repaint or navigation is a reaction`() {
        listOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        ).forEach { assertTrue("type $it", SemanticFallbackPolicy.isReaction(it)) }
    }

    @Test
    fun `a new window from any app is navigation`() {
        // Settings' search bar opens com.google.android.settings.intelligence: the reaction
        // comes from a different package than the one tapped, and must still count.
        assertTrue(SemanticFallbackPolicy.isNavigation(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(SemanticFallbackPolicy.isNavigation(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertFalse(SemanticFallbackPolicy.isNavigation(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(SemanticFallbackPolicy.isNavigation(AccessibilityEvent.TYPE_VIEW_CLICKED))
    }

    @Test
    fun `reaction wins over node availability`() {
        assertFalse(
            SemanticFallbackPolicy.shouldFallBack(
                uiMutatedAfterDispatch = true,
                hasClickableNode = false,
            ),
        )
    }
}
