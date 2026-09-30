package com.aura.mcp.cache

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import com.aura.mcp.tools.ScreenSignature
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * As of 2026-08-18 [PerceptionCache] has **two** producers — `perceive_screen` and
 * `read_screen` — sharing one som_id namespace, last-writer-wins.
 *
 * That is correct: both are honest captures of the same screen, and `update()` swaps
 * coordinates, signature, generation and activity as ONE immutable reference, so a
 * `resolve` can never read one capture's coordinates against another's freshness
 * check. It is also the sharpest new edge in the design, because a som_id is just an
 * integer — nothing in `tap(12)` says which look produced the 12. These pin the
 * ordering so a future change cannot quietly make the wrong capture answer.
 */
class TwoProducerSomIdTest {

    private fun element(somId: Int, x: Int, y: Int) = DetectedElement(
        somId = somId,
        bbox = BBox(x1 = x, y1 = y, x2 = x + 100, y2 = y + 100),
        elementType = "Button",
        label = "target-$somId",
        confidence = 1.0f,
    )

    /** Fixed counters: nothing claims the screen moved, so resolve takes the fast path. */
    private fun stableCache() = PerceptionCache(
        generation = { 7L },
        
        activity = { 3L },
    )

    private fun signature(content: Long) = ScreenSignature.Signature(
        layout = 100L,
        content = content,
        packageName = "com.example",
        nodeCount = 40,
        treeBlind = false,
    )

    /**
     * The value proposition of `read_screen`. If a read did not ground taps, every tap
     * after one would fall back to a full `perceive_screen` and the cheap look would
     * save nothing.
     */
    @Test
    fun `a read grounds a tap on an unchanged screen`() = runBlocking {
        val cache = stableCache()

        cache.update(listOf(element(12, 400, 800)), signature = signature(1L), activityAtCapture = 3L)

        val resolved = cache.resolve(12)
        assertIs<PerceptionCache.Resolution.Fresh>(resolved)
        assertEquals(450, resolved.x)
        assertEquals(850, resolved.y)
    }

    /**
     * Last writer wins, and it must be the LAST one — a perceive after a read
     * renumbers the screen, and answering from the read's numbering would dispatch a
     * real gesture at whatever used to be 12.
     */
    @Test
    fun `perceive after read wins the som_id`() = runBlocking {
        val cache = stableCache()

        // read_screen numbered this screen first…
        cache.update(listOf(element(12, 400, 800)), signature = signature(1L), activityAtCapture = 3L)
        // …then perceive_screen looked again and numbered it differently.
        cache.update(listOf(element(12, 100, 200)), signature = signature(1L), activityAtCapture = 3L)

        val resolved = cache.resolve(12)
        assertIs<PerceptionCache.Resolution.Fresh>(resolved)
        assertEquals(150, resolved.x, "expected perceive's numbering, got the earlier read's")
        assertEquals(250, resolved.y)
    }

    /** Symmetric: a read after a perceive is just as authoritative. */
    @Test
    fun `read after perceive wins the som_id`() = runBlocking {
        val cache = stableCache()

        cache.update(listOf(element(12, 100, 200)), signature = signature(1L), activityAtCapture = 3L)
        cache.update(listOf(element(12, 400, 800)), signature = signature(1L), activityAtCapture = 3L)

        val resolved = cache.resolve(12)
        assertIs<PerceptionCache.Resolution.Fresh>(resolved)
        assertEquals(450, resolved.x)
        assertEquals(850, resolved.y)
    }

    /**
     * A later capture REPLACES rather than merges. If it merged, a som_id the newer
     * look no longer has would keep resolving from the older one — pointing at an
     * element that is no longer on the screen.
     */
    @Test
    fun `a later capture drops som_ids the earlier one had`(): Unit = runBlocking {
        val cache = stableCache()

        cache.update(
            listOf(element(1, 0, 0), element(2, 0, 100), element(3, 0, 200)),
            signature = signature(1L),
            activityAtCapture = 3L,
        )
        cache.update(listOf(element(1, 0, 0)), signature = signature(1L), activityAtCapture = 3L)

        assertIs<PerceptionCache.Resolution.Fresh>(cache.resolve(1))
        assertIs<PerceptionCache.Resolution.Unknown>(cache.resolve(3))
    }

    /**
     * The freshness check must come from the SAME capture as the coordinates. A read
     * that recorded an activity counter from before its own tree read would look fresh
     * while describing a screen that had already moved on.
     */
    @Test
    fun `a capture whose activity counter predates the live one is not trusted blindly`(): Unit = runBlocking {
        // Live activity has moved past what the capture recorded, and no probe is
        // wired, so the cache cannot confirm the screen is the same. Fail closed.
        val cache = PerceptionCache(generation = { 7L }, activity = { 9L })

        cache.update(listOf(element(12, 400, 800)), signature = signature(1L), activityAtCapture = 3L)

        assertIs<PerceptionCache.Resolution.Stale>(cache.resolve(12))
    }
}
