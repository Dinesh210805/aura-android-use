package com.aura.aura_ui.agent.strategy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAfterActionTest {

    @Test fun `a successful gesture gets a look`() {
        assertTrue(ScreenAfterAction.shouldObserve(listOf("tap" to true)))
    }

    @Test fun `a refused or failed gesture does not`() {
        assertFalse(ScreenAfterAction.shouldObserve(listOf("tap" to false)))
    }

    @Test fun `a tap refused as stale is recognised, other failures are not`() {
        assertTrue(ScreenAfterAction.isStaleRefusal("som_id 89 is STALE: the screen changed on its own"))
        assertFalse(ScreenAfterAction.isStaleRefusal("som_id 999 not found in perception cache."))
    }

    @Test fun `tools that change no pixels do not`() {
        assertFalse(ScreenAfterAction.shouldObserve(listOf("volume_up" to true, "recall_memory" to true)))
    }

    @Test fun `a turn that already looked after its gesture is not read twice`() {
        assertFalse(ScreenAfterAction.shouldObserve(listOf("tap" to true, "read_screen" to true)))
    }

    @Test fun `a look BEFORE the gesture is stale and does not count`() {
        assertTrue(ScreenAfterAction.shouldObserve(listOf("read_screen" to true, "type_text" to true)))
    }

    @Test fun `the block is recognisable for eviction`() {
        assertTrue(ScreenAfterAction.isBlock(ScreenAfterAction.block("SCREEN 1080x2400")))
        assertFalse(ScreenAfterAction.isBlock("SCREEN 1080x2400"))
    }
}
