package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAttachmentsTest {
    @Test
    fun `a photo decodes at the smallest power of two that still covers the thumbnail`() {
        assertEquals(1, sampleSize(200, 150, 216))
        assertEquals(16, sampleSize(4000, 3000, 216))
        assertEquals(2, sampleSize(3000, 4000, 1800))
        assertEquals(1, sampleSize(0, 0, 216))
    }
}
