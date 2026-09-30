package com.aura.aura_ui.accessibility.gesture

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
    fun `reaction wins over node availability`() {
        assertFalse(
            SemanticFallbackPolicy.shouldFallBack(
                uiMutatedAfterDispatch = true,
                hasClickableNode = false,
            ),
        )
    }
}
