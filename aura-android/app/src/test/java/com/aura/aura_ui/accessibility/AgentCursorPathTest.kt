package com.aura.aura_ui.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/** [pointAlong]: where the agent cursor sits along a stroke at a given fraction of its length. */
class AgentCursorPathTest {

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.01f)
        assertEquals(expected.second, actual.second, 0.01f)
    }

    private val ell = listOf(0f to 0f, 100f to 0f, 100f to 100f)

    @Test fun `ends of the stroke`() {
        assertPoint(0f to 0f, pointAlong(ell, 0f))
        assertPoint(100f to 100f, pointAlong(ell, 1f))
    }

    @Test fun `fraction is by length across segments`() {
        assertPoint(100f to 0f, pointAlong(ell, 0.5f))
        assertPoint(100f to 50f, pointAlong(ell, 0.75f))
    }

    @Test fun `out of range fractions clamp and a single point stays put`() {
        assertPoint(0f to 0f, pointAlong(ell, -1f))
        assertPoint(100f to 100f, pointAlong(ell, 2f))
        assertPoint(5f to 5f, pointAlong(listOf(5f to 5f), 0.5f))
    }

    @Test fun `zero length segments are skipped`() {
        assertPoint(50f to 0f, pointAlong(listOf(0f to 0f, 0f to 0f, 100f to 0f), 0.5f))
    }
}
