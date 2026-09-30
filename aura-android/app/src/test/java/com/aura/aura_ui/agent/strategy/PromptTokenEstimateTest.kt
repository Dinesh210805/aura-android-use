package com.aura.aura_ui.agent.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTokenEstimateTest {
    @Test fun `text-only prompt uses the chars-per-token divisor`() {
        assertEquals(100, PromptTokenEstimate.estimate(totalTextChars = 400, imageCount = 0))
    }

    @Test fun `O2 - each image adds the flat per-image charge`() {
        val withoutImage = PromptTokenEstimate.estimate(totalTextChars = 400, imageCount = 0)
        val withImage = PromptTokenEstimate.estimate(totalTextChars = 400, imageCount = 1)
        assertEquals(PromptTokenEstimate.TOKENS_PER_IMAGE, withImage - withoutImage)
    }

    @Test fun `O2 - a screenshot can no longer hide from the budget`() {
        // The regression this guards: one image, tiny text — the old estimate said ~0 tokens.
        assertTrue(PromptTokenEstimate.estimate(totalTextChars = 10, imageCount = 1) >= 1_500)
    }

    @Test fun `empty prompt estimates zero`() {
        assertEquals(0, PromptTokenEstimate.estimate(0, 0))
    }
}
