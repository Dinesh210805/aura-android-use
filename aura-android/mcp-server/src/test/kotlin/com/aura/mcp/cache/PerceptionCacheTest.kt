package com.aura.mcp.cache

import com.aura.mcp.bridge.BBox
import com.aura.mcp.bridge.DetectedElement
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PerceptionCache].
 *
 * Tests verify:
 * - som_id → (center_x, center_y) resolution from full-resolution device pixels
 * - Cache replacement on new perceive_screen calls
 * - Error handling for unknown som_ids
 * - Thread safety semantics (volatile reads)
 * - Cache diagnostics (cachedSomIds, contains)
 */
class PerceptionCacheTest {

    @Test
    fun `update stores som_id to coordinates mapping`() {
        val cache = PerceptionCache()
        val elements = listOf(
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 100, y1 = 200, x2 = 300, y2 = 400),
                elementType = "button",
                label = "Search",
                confidence = 0.95f,
            ),
            DetectedElement(
                somId = 2,
                bbox = BBox(x1 = 500, y1 = 600, x2 = 700, y2 = 800),
                elementType = "text_field",
                label = "Username",
                confidence = 0.90f,
            ),
        )

        cache.update(elements)

        // Verify som_id → center coordinates mapping
        val coords1 = cache.getCoordinates(1)
        assertEquals(200, coords1?.first)  // (100 + 300) / 2 = 200
        assertEquals(300, coords1?.second) // (200 + 400) / 2 = 300

        val coords2 = cache.getCoordinates(2)
        assertEquals(600, coords2?.first)  // (500 + 700) / 2 = 600
        assertEquals(700, coords2?.second) // (600 + 800) / 2 = 700
    }

    @Test
    fun `getCoordinates returns null for unknown som_id`() {
        val cache = PerceptionCache()
        val elements = listOf(
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "OK",
                confidence = 1.0f,
            ),
        )
        cache.update(elements)

        assertNull(cache.getCoordinates(999))
    }

    @Test
    fun `contains checks som_id existence`() {
        val cache = PerceptionCache()
        val elements = listOf(
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "OK",
                confidence = 1.0f,
            ),
        )
        cache.update(elements)

        assertTrue(cache.contains(1))
        assertFalse(cache.contains(2))
    }

    @Test
    fun `cachedSomIds returns sorted list of currently cached ids`() {
        val cache = PerceptionCache()
        val elements = listOf(
            DetectedElement(
                somId = 3,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "C",
                confidence = 1.0f,
            ),
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "A",
                confidence = 1.0f,
            ),
            DetectedElement(
                somId = 2,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "B",
                confidence = 1.0f,
            ),
        )
        cache.update(elements)

        val cached = cache.cachedSomIds()
        assertEquals(listOf(1, 2, 3), cached)
    }

    @Test
    fun `update replaces entire cache on new perceive_screen call`() {
        val cache = PerceptionCache()

        // First perceive_screen
        val elements1 = listOf(
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "Old",
                confidence = 1.0f,
            ),
        )
        cache.update(elements1)
        assertTrue(cache.contains(1))

        // Second perceive_screen with different elements
        val elements2 = listOf(
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 200, y1 = 300, x2 = 400, y2 = 500),
                elementType = "button",
                label = "New",
                confidence = 1.0f,
            ),
            DetectedElement(
                somId = 2,
                bbox = BBox(x1 = 600, y1 = 700, x2 = 800, y2 = 900),
                elementType = "text_field",
                label = "Fresh",
                confidence = 1.0f,
            ),
        )
        cache.update(elements2)

        // Old coordinates should be replaced
        val coords = cache.getCoordinates(1)
        assertEquals(300, coords?.first)  // (200 + 400) / 2
        assertEquals(400, coords?.second) // (300 + 500) / 2

        // New element should be available
        assertTrue(cache.contains(2))
    }

    @Test
    fun `stale cache elements are not visible after update`() {
        val cache = PerceptionCache()

        // First perceive_screen has som_id 1 and 2
        val elements1 = listOf(
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "A",
                confidence = 1.0f,
            ),
            DetectedElement(
                somId = 2,
                bbox = BBox(x1 = 200, y1 = 200, x2 = 300, y2 = 300),
                elementType = "button",
                label = "B",
                confidence = 1.0f,
            ),
        )
        cache.update(elements1)
        assertEquals(listOf(1, 2), cache.cachedSomIds())

        // Second perceive_screen has only som_id 1 and 3 (no 2)
        val elements2 = listOf(
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "A",
                confidence = 1.0f,
            ),
            DetectedElement(
                somId = 3,
                bbox = BBox(x1 = 400, y1 = 400, x2 = 500, y2 = 500),
                elementType = "button",
                label = "C",
                confidence = 1.0f,
            ),
        )
        cache.update(elements2)

        // Old som_id 2 should no longer be in cache
        assertFalse(cache.contains(2))
        assertNull(cache.getCoordinates(2))
        assertEquals(listOf(1, 3), cache.cachedSomIds())
    }

    @Test
    fun `clear resets cache to empty state`() {
        val cache = PerceptionCache()
        val elements = listOf(
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
                elementType = "button",
                label = "Test",
                confidence = 1.0f,
            ),
        )
        cache.update(elements)
        assertTrue(cache.contains(1))

        cache.clear()
        assertFalse(cache.contains(1))
        assertNull(cache.getCoordinates(1))
        assertEquals(emptyList(), cache.cachedSomIds())
    }

    @Test
    fun `empty cache is safe to query`() {
        val cache = PerceptionCache()

        assertNull(cache.getCoordinates(1))
        assertFalse(cache.contains(1))
        assertEquals(emptyList(), cache.cachedSomIds())
    }

    @Test
    fun `large number of elements handled correctly`() {
        val cache = PerceptionCache()
        val elements = (1..1000).map { id ->
            DetectedElement(
                somId = id,
                bbox = BBox(x1 = id * 10, y1 = id * 20, x2 = id * 10 + 100, y2 = id * 20 + 100),
                elementType = "element",
                label = "Item $id",
                confidence = 0.95f,
            )
        }
        cache.update(elements)

        assertEquals(1000, cache.cachedSomIds().size)
        assertTrue(cache.contains(1))
        assertTrue(cache.contains(500))
        assertTrue(cache.contains(1000))

        val coords500 = requireNotNull(cache.getCoordinates(500))
        assertEquals(5050, coords500.first)  // (500*10 + 500*10 + 100) / 2 = 5050
        assertEquals(10050, coords500.second) // (500*20 + 500*20 + 100) / 2 = 10050
    }

    @Test
    fun `bbox center calculation is correct`() {
        val cache = PerceptionCache()
        val elements = listOf(
            // Odd dimensions to verify integer division
            DetectedElement(
                somId = 1,
                bbox = BBox(x1 = 1, y1 = 1, x2 = 10, y2 = 10),
                elementType = "button",
                label = "Test",
                confidence = 1.0f,
            ),
        )
        cache.update(elements)

        val coords = requireNotNull(cache.getCoordinates(1))
        assertEquals(5, coords.first)  // (1 + 10) / 2 = 5
        assertEquals(5, coords.second) // (1 + 10) / 2 = 5
    }

    // ── P1: temporal staleness — som_ids from a dead screen must not resolve ──

    private fun element(id: Int) = DetectedElement(
        somId = id,
        bbox = BBox(x1 = 100, y1 = 200, x2 = 300, y2 = 400),
        elementType = "button",
        label = "Search",
        confidence = 0.95f,
    )

    /** A screen with one bounded, clickable node — hashes to a real (non-blind) signature. */
    private fun treeJson(label: String) =
        """{"package_name":"com.example","elements":[
             {"className":"android.widget.Button","text":"$label","isClickable":true,
              "bounds":{"left":100,"top":200,"right":300,"bottom":400}}]}"""

    private fun sig(label: String) = com.aura.mcp.tools.ScreenSignature.of(treeJson(label))

    @Test
    fun `resolve returns Fresh coordinates right after update`() = runBlocking {
        var gen = 0L
        val cache = PerceptionCache(generation = { gen }, activity = { 0L })
        cache.update(listOf(element(1)))
        val r = cache.resolve(1)
        assertTrue(r is PerceptionCache.Resolution.Fresh, "expected Fresh, got $r")
        assertEquals(200, (r as PerceptionCache.Resolution.Fresh).x)
    }

    @Test
    fun `resolve returns Stale after the screen generation advances`() = runBlocking {
        var gen = 0L
        val cache = PerceptionCache(generation = { gen }, activity = { 0L })
        cache.update(listOf(element(1)))
        gen = 1L // a WRITE tool (gesture/launch/navigation) ran since the perceive
        val r = cache.resolve(1)
        assertTrue(r is PerceptionCache.Resolution.Stale, "expected Stale, got $r")
        assertTrue((r as PerceptionCache.Resolution.Stale).reason.contains("changed"))
    }

    @Test
    fun `an old look at an untouched screen still resolves`() = runBlocking {
        // F1: a 30 s age rule refused correct taps on an idle screen. Age proves nothing;
        // the counters do.
        val cache = PerceptionCache(generation = { 0L }, activity = { 0L })
        cache.update(listOf(element(1)))
        assertTrue(cache.resolve(1) is PerceptionCache.Resolution.Fresh)
    }

    @Test
    fun `resolve returns Unknown for an unknown som_id even when fresh`() = runBlocking {
        val cache = PerceptionCache(generation = { 0L }, activity = { 0L })
        cache.update(listOf(element(1)))
        assertTrue(cache.resolve(999) is PerceptionCache.Resolution.Unknown)
    }

    @Test
    fun `a new update makes the cache fresh again`() = runBlocking {
        var gen = 0L
        val cache = PerceptionCache(generation = { gen }, activity = { 0L })
        cache.update(listOf(element(1)))
        gen = 3L
        cache.update(listOf(element(1))) // fresh perceive re-captures the generation
        assertTrue(cache.resolve(1) is PerceptionCache.Resolution.Fresh)
    }

    @Test
    fun `an empty cache resolves Unknown`() = runBlocking {
        val cache = PerceptionCache(generation = { 0L }, activity = { 0L })
        assertTrue(cache.resolve(1) is PerceptionCache.Resolution.Unknown)
    }

    // ── Precision: the generation counter over-reports, the screen is the truth ──

    @Test
    fun `a WRITE tool that changed no pixels does not invalidate the cache`() = runBlocking {
        // volume_up / mute / media_control are WRITE-scoped and bump the generation,
        // but they change nothing on screen. Re-perceiving after one is pure waste.
        var gen = 0L
        val cache = PerceptionCache(
            generation = { gen },
            
            activity = { 0L },
            screenProbe = { treeJson("Play") },
        )
        cache.update(listOf(element(1)), sig("Play"))
        gen = 1L

        assertTrue(
            cache.resolve(1) is PerceptionCache.Resolution.Fresh,
            "an unchanged screen must rescue the cache the counter gave up on",
        )
    }

    @Test
    fun `a screen that moved on its own invalidates even with no tool call`() = runBlocking {
        // A notification banner, a splash finishing, an incoming call. The generation
        // counter cannot see any of these — only passive event activity can.
        var events = 0L
        val cache = PerceptionCache(
            generation = { 0L },
            
            activity = { events },
            screenProbe = { treeJson("Pause") }, // the screen underneath is different now
        )
        cache.update(listOf(element(1)), sig("Play"))
        events = 1L

        val r = cache.resolve(1)
        assertTrue(r is PerceptionCache.Resolution.Stale, "expected Stale, got $r")
        assertTrue((r as PerceptionCache.Resolution.Stale).reason.contains("settling"))
    }

    @Test
    fun `a real screen change after a gesture still invalidates`() = runBlocking {
        var gen = 0L
        val cache = PerceptionCache(
            generation = { gen },
            
            activity = { 0L },
            screenProbe = { treeJson("Checkout") },
        )
        cache.update(listOf(element(1)), sig("Cart"))
        gen = 1L
        assertTrue(cache.resolve(1) is PerceptionCache.Resolution.Stale)
    }

    @Test
    fun `a tree-blind screen fails closed rather than rescuing`() = runBlocking {
        // WebView / game: the hash is constant while the screen moves freely, so
        // "signature unchanged" proves nothing and must not resolve coordinates.
        var gen = 0L
        val blind = com.aura.mcp.tools.ScreenSignature.blind("com.game")
        val cache = PerceptionCache(
            generation = { gen },
            
            activity = { 0L },
            screenProbe = { BLIND_JSON },
        )
        cache.update(listOf(element(1)), blind)
        gen = 1L
        assertTrue(cache.resolve(1) is PerceptionCache.Resolution.Stale)
    }

    // ── The element, not the screen: a feed that never stops moving must still be tappable ──

    /** A screen with a fixed nav button plus one element whose text churns every frame. */
    private fun feedJson(views: String, tabLabel: String = "Profile") =
        """{"package_name":"com.instagram.android","elements":[
             {"className":"android.widget.Button","text":"$tabLabel","isClickable":true,
              "bounds":{"left":1080,"top":2560,"right":1160,"bottom":2660}},
             {"className":"android.widget.TextView","text":"$views views",
              "bounds":{"left":40,"top":1200,"right":900,"bottom":1260}}]}"""

    private fun feedCapture(views: String, tabLabel: String = "Profile") =
        com.aura.mcp.tools.UiTreeToElements.extract(feedJson(views, tabLabel), minElements = 1)

    private fun somOf(elements: List<DetectedElement>, label: String) =
        elements.first { it.label == label }.somId

    @Test
    fun `volatile text elsewhere on screen does not invalidate an unmoved target`() = runBlocking {
        // The Instagram reel case (2026-09-08): the whole-screen content hash can never
        // match on a playing feed, so every tap came back STALE and the run deadlocked.
        var events = 0L
        val captured = feedCapture("1204")
        val cache = PerceptionCache(
            generation = { 0L },
            
            activity = { events },
            screenProbe = { feedJson("1205") }, // only the view counter moved
        )
        cache.update(captured, com.aura.mcp.tools.ScreenSignature.of(feedJson("1204")))
        events = 1L

        val r = cache.resolve(somOf(captured, "Profile"))
        assertTrue(r is PerceptionCache.Resolution.Fresh, "expected Fresh, got $r")
    }

    @Test
    fun `a target whose label only ticked a number is still the same element`() = runBlocking {
        // F1, the Maps run: "Driving mode: 7 hours 13 minutes" became "...14 minutes" between
        // the look and the tap, and every tap on it came back STALE.
        var events = 0L
        val captured = feedCapture("1204", tabLabel = "Driving mode: 7 hours 13 minutes")
        val cache = PerceptionCache(
            generation = { 0L },
            activity = { events },
            screenProbe = { feedJson("1204", tabLabel = "Driving mode: 7 hours 14 minutes") },
        )
        cache.update(captured, com.aura.mcp.tools.ScreenSignature.of(feedJson("1204")))
        events = 1L

        val r = cache.resolve(somOf(captured, "Driving mode: 7 hours 13 minutes"))
        assertTrue(r is PerceptionCache.Resolution.Fresh, "expected Fresh, got $r")
    }

    @Test
    fun `a list row that re-sorted to a different name still invalidates`() = runBlocking {
        // The hazard digit-blanking must not open: WhatsApp moves a chat to the top when a
        // message arrives, so the row under the finger is now someone else.
        var events = 0L
        val captured = feedCapture("1204", tabLabel = "Mom, 2 unread")
        val cache = PerceptionCache(
            generation = { 0L },
            activity = { events },
            screenProbe = { feedJson("1204", tabLabel = "Boss, 3 unread") },
        )
        cache.update(captured, com.aura.mcp.tools.ScreenSignature.of(feedJson("1204")))
        events = 1L

        val r = cache.resolve(somOf(captured, "Mom, 2 unread"))
        assertTrue(r is PerceptionCache.Resolution.Stale, "expected Stale, got $r")
    }

    @Test
    fun `a target that changed under identical bounds still invalidates`() = runBlocking {
        // The hazard the narrow check must NOT introduce: a row rebinding new data in
        // place. Same bounds, same class, different label — tapping it opens the wrong
        // thing, so this has to stay Stale.
        var events = 0L
        val captured = feedCapture("1204")
        val cache = PerceptionCache(
            generation = { 0L },
            
            activity = { events },
            screenProbe = { feedJson("1204", tabLabel = "Notifications") },
        )
        cache.update(captured, com.aura.mcp.tools.ScreenSignature.of(feedJson("1204")))
        events = 1L

        val r = cache.resolve(somOf(captured, "Profile"))
        assertTrue(r is PerceptionCache.Resolution.Stale, "expected Stale, got $r")
    }

    @Test
    fun `with no probe wired the cache keeps its old conservative behaviour`() = runBlocking {
        var gen = 0L
        val cache = PerceptionCache(generation = { gen }, activity = { 0L })
        cache.update(listOf(element(1)), sig("Play"))
        gen = 1L
        assertTrue(cache.resolve(1) is PerceptionCache.Resolution.Stale)
    }

    // ── hasLabel: diagnostic-only tracking of blank-label elements ──

    @Test
    fun `hasLabel is true for an element with a non-blank label`() {
        val cache = PerceptionCache()
        cache.update(listOf(element(1))) // element(id) always sets label = "Search"
        assertEquals(true, cache.hasLabel(1))
    }

    @Test
    fun `hasLabel is false for an element with a blank label`() {
        val cache = PerceptionCache()
        val blank = DetectedElement(
            somId = 1,
            bbox = BBox(x1 = 0, y1 = 0, x2 = 100, y2 = 100),
            elementType = "LinearLayout",
            label = "",
            confidence = 1.0f,
        )
        cache.update(listOf(blank))
        assertEquals(false, cache.hasLabel(1))
    }

    @Test
    fun `hasLabel is null for an unknown som_id`() {
        val cache = PerceptionCache()
        cache.update(listOf(element(1)))
        assertNull(cache.hasLabel(999))
    }

    @Test
    fun `clear resets hasLabel tracking too`() {
        val cache = PerceptionCache()
        cache.update(listOf(element(1)))
        cache.clear()
        assertNull(cache.hasLabel(1))
    }

    private companion object {
        /** What the :app validator emits for a WebView / Canvas / game surface. */
        const val BLIND_JSON = """{"package_name":"com.game","validation_failed":true}"""
    }
}
