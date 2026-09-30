package com.aura.aura_ui.agent.strategy

import org.junit.Assert.assertEquals
import org.junit.Test

class PerceptionEvictionPolicyTest {

    @Test
    fun `older perceptions are stale, the latest is kept`() {
        // messages: perceive, tap(null), perceive  ->  only index 0 is stale
        val perTool = listOf("perceive_screen", null, "perceive_screen")
        assertEquals(setOf(0), PerceptionEvictionPolicy.staleIndices(perTool))
    }

    @Test
    fun `three perceptions collapse all but the last`() {
        val perTool = listOf("perceive_screen", "read_screen", null, "get_screenshot")
        assertEquals(setOf(0, 1), PerceptionEvictionPolicy.staleIndices(perTool))
    }

    @Test
    fun `single perception is never stale`() {
        assertEquals(emptySet<Int>(), PerceptionEvictionPolicy.staleIndices(listOf(null, "perceive_screen", null)))
    }

    @Test
    fun `no perception yields no stale indices`() {
        assertEquals(emptySet<Int>(), PerceptionEvictionPolicy.staleIndices(listOf(null, null)))
        assertEquals(emptySet<Int>(), PerceptionEvictionPolicy.staleIndices(emptyList()))
    }

    // ── O4: the screenshot must never outlive its evicted text twin ──

    /**
     * The agent escalates to `perceive_screen` precisely because the tree was not
     * enough. If a following `read_screen` — its DEFAULT look, so it will happen —
     * evicted that image, the escalation would have to be paid for twice: another
     * capture, another CV pass, another turn. A cheap tree read supplements the
     * picture; it does not replace it.
     */
    @Test
    fun `a cheap tree read does not evict the image the agent escalated for`() {
        assertEquals(
            false,
            PerceptionEvictionPolicy.shouldDropScreenshot(
                turnPerceptionTools = listOf("read_screen"),
                newScreenshotArrived = false,
            ),
        )
    }

    /**
     * The rule itself still stands for any text-only perception that genuinely does
     * supersede the visual state — it is the trigger list that narrowed, not the
     * policy. Pinned so a future tool cannot be added to PERCEPTION_TOOLS and silently
     * inherit "never evicts".
     */
    @Test
    fun `a superseding text-only perception still drops the old screenshot`() {
        assertEquals(
            true,
            PerceptionEvictionPolicy.shouldDropScreenshot(
                turnPerceptionTools = listOf("some_future_text_only_perception"),
                newScreenshotArrived = false,
            ),
        )
    }

    /** A mixed turn still evicts: one superseding look is enough to stale the pixels. */
    @Test
    fun `a superseding look alongside a cheap read still drops the screenshot`() {
        assertEquals(
            true,
            PerceptionEvictionPolicy.shouldDropScreenshot(
                turnPerceptionTools = listOf("read_screen", "some_future_text_only_perception"),
                newScreenshotArrived = false,
            ),
        )
    }

    @Test
    fun `a new screenshot this turn keeps the image channel (it is replaced, not dropped)`() {
        assertEquals(
            false,
            PerceptionEvictionPolicy.shouldDropScreenshot(
                turnPerceptionTools = listOf("perceive_screen"),
                newScreenshotArrived = true,
            ),
        )
    }

    @Test
    fun `a non-perception turn leaves the screenshot alone`() {
        assertEquals(
            false,
            PerceptionEvictionPolicy.shouldDropScreenshot(
                turnPerceptionTools = emptyList(),
                newScreenshotArrived = false,
            ),
        )
    }

    // ── O9: compaction must never summarize away the newest screenshot ──

    @Test
    fun `compaction keep-count extends to cover the newest image message`() {
        // 20 messages, newest image at index 10 → 10 messages from the image to the
        // end; keeping only the configured 6 would summarize the image away.
        assertEquals(10, PerceptionEvictionPolicy.compactKeepCount(messageCount = 20, lastImageIndex = 10, configuredKeep = 6))
    }

    @Test
    fun `configured keep wins when the image is already inside the window`() {
        assertEquals(6, PerceptionEvictionPolicy.compactKeepCount(messageCount = 20, lastImageIndex = 18, configuredKeep = 6))
    }

    @Test
    fun `no image in history uses the configured keep`() {
        assertEquals(6, PerceptionEvictionPolicy.compactKeepCount(messageCount = 20, lastImageIndex = -1, configuredKeep = 6))
    }
}
