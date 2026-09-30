package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveModelResolverTest {

    // ── the configured preference wins when the key actually has it ──────────

    @Test fun `the configured preferred model outranks every heuristic`() {
        // Before this, pick() ignored CompanionConfig.LIVE_MODEL entirely whenever ListModels
        // succeeded — so changing the configured model appeared to do nothing at all.
        val pick = LiveModelResolver.pick(
            listOf(
                "models/gemini-2.5-flash-native-audio-latest",
                "models/gemini-3.1-flash-live-preview",
            ),
        )
        assertEquals(CompanionConfig.LIVE_MODEL, pick)
    }

    @Test fun `the preferred model matches even without the models slash prefix`() {
        // ListModels is not guaranteed to return the same prefix form as our constant.
        val pick = LiveModelResolver.pick(
            listOf("gemini-3.1-flash-live-preview", "models/gemini-2.5-flash-native-audio-latest"),
            preferred = "models/gemini-3.1-flash-live-preview",
        )
        assertEquals("gemini-3.1-flash-live-preview", pick)
    }

    @Test fun `falls back to the heuristics when the key lacks the preferred model`() {
        val pick = LiveModelResolver.pick(
            listOf("models/gemini-2.5-flash-native-audio-latest", "models/gemini-live-2.5-flash-preview"),
            preferred = "models/not-on-this-key",
        )
        assertEquals("models/gemini-2.5-flash-native-audio-latest", pick)
    }

    @Test fun `prefers a native-audio 2_5 live model`() {
        val pick = LiveModelResolver.pick(
            listOf(
                "models/gemini-1.5-pro",
                "models/gemini-live-2.5-flash-preview",
                "models/gemini-2.5-flash-native-audio-preview-12-2025",
            ),
        )
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", pick)
    }

    @Test fun `falls back to a half-cascade live model when no native audio`() {
        val pick = LiveModelResolver.pick(
            listOf("models/gemini-1.5-pro", "models/gemini-live-2.5-flash-preview"),
        )
        assertEquals("models/gemini-live-2.5-flash-preview", pick)
    }

    @Test fun `returns null when no live-capable model is offered`() {
        assertNull(LiveModelResolver.pick(listOf("models/gemini-1.5-pro", "models/text-embedding-004")))
    }

    // ── latency: thinking variants deliberate for seconds every turn ──────────

    @Test fun `never picks a thinking dialog variant when a plain native-audio model exists`() {
        // ListModels order is arbitrary — the thinking variant listing FIRST must not win.
        val pick = LiveModelResolver.pick(
            listOf(
                "models/gemini-2.5-flash-exp-native-audio-thinking-dialog",
                "models/gemini-2.5-flash-native-audio-preview-12-2025",
            ),
        )
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", pick)
    }

    @Test fun `prefers a half-cascade live model over a thinking native-audio variant`() {
        // A thinking model's per-turn deliberation dominates any half-cascade quality delta.
        val pick = LiveModelResolver.pick(
            listOf(
                "models/gemini-2.5-flash-exp-native-audio-thinking-dialog",
                "models/gemini-live-2.5-flash-preview",
            ),
        )
        assertEquals("models/gemini-live-2.5-flash-preview", pick)
    }

    @Test fun `a thinking variant is still better than nothing`() {
        val pick = LiveModelResolver.pick(
            listOf("models/gemini-1.5-pro", "models/gemini-2.5-flash-exp-native-audio-thinking-dialog"),
        )
        assertEquals("models/gemini-2.5-flash-exp-native-audio-thinking-dialog", pick)
    }
}
