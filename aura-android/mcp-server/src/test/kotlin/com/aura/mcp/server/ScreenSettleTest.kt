package com.aura.mcp.server

import com.aura.mcp.bridge.DeviceEvent
import com.aura.mcp.bridge.UiTreeBridge
import com.aura.mcp.bridge.UiTreeSnapshot
import com.aura.mcp.cache.ScreenActivity
import com.aura.mcp.tools.ScreenSignature
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E1 — harness-side settle. The contract under test is that **the signature decides
 * whether the screen changed and events only decide how long we wait** — the previous
 * `eventCount > 0` rule reported "changed" for every recomposition storm and marquee.
 */
class ScreenSettleTest {

    private val config = ScreenSettle.Config(
        noChangeProbeMs = 250,
        quietMs = 300,
        maxMs = 1_500,
        ignoredPackages = setOf("com.aura.aura_ui.feature.debug"),
    )

    @Test
    fun `quiet start with an unchanged screen settles fast and reports no change`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(clock) // no scripted steps → every drain times out empty
        val before = ScreenSignature.of(bridge.treeJson)

        val outcome = ScreenSettle.await(bridge, config, before, clock::now)

        assertTrue(outcome.settled)
        assertFalse(outcome.screenChanged, "identical signature must not read as a change")
        assertEquals(0, outcome.eventCount)
        assertTrue(
            outcome.elapsedMs <= config.noChangeProbeMs,
            "the did-nothing case must exit on the short probe, not the old 600ms wait",
        )
    }

    @Test
    fun `a screen that changed without emitting events is still reported as changed`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(clock)
        val before = ScreenSignature.of(tree(label = "Play"))
        bridge.treeJson = tree(label = "Pause") // changed silently

        val outcome = ScreenSettle.await(bridge, config, before, clock::now)

        assertTrue(outcome.settled)
        assertTrue(outcome.screenChanged, "silence must not be mistaken for stillness")
    }

    @Test
    fun `waits through an event burst then settles on a quiet window`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(
            clock,
            step { _, _ -> clock.advance(0); emptyList() }, // backlog clear
            step { _, _ -> clock.advance(100); listOf(ev("com.amazon")) },
            step { t, _ -> clock.advance(t); listOf(ev("com.amazon"), ev("com.amazon")) },
            step { t, _ -> clock.advance(t); emptyList() }, // quiet window → settled
        )
        val before = ScreenSignature.of(tree(label = "Cart"))
        bridge.treeJson = tree(label = "Checkout")

        val outcome = ScreenSettle.await(bridge, config, before, clock::now)

        assertTrue(outcome.settled)
        assertTrue(outcome.screenChanged)
        assertEquals(3, outcome.eventCount)
    }

    @Test
    fun `a perpetual animator on a stable screen exits early instead of burning the cap`() = runBlocking<Unit> {
        val clock = FakeClock()
        val storm = step { t: Long, _: Int -> clock.advance(t); listOf(ev("com.spinner")) }
        val bridge = ScriptedBridge(
            clock,
            step { _, _ -> clock.advance(0); emptyList() },
            step { _, _ -> clock.advance(50); listOf(ev("com.spinner")) },
            storm, storm, storm, storm, storm, storm, // never goes quiet
        )
        val before = ScreenSignature.of(bridge.treeJson) // screen underneath never moves

        val outcome = ScreenSettle.await(bridge, config, before, clock::now)

        assertTrue(outcome.settled, "two identical samples prove the noise is animation")
        assertFalse(outcome.screenChanged, "a spinner ticking is not a screen change")
        assertTrue(
            outcome.elapsedMs < config.maxMs,
            "must exit on signature stability (~650ms), not run to the 1500ms cap",
        )
    }

    @Test
    fun `a screen genuinely still changing runs to the cap`() = runBlocking<Unit> {
        val clock = FakeClock()
        val storm = step { t: Long, _: Int -> clock.advance(t); listOf(ev("com.loader")) }
        val bridge = ScriptedBridge(
            clock,
            step { _, _ -> clock.advance(0); emptyList() },
            step { _, _ -> clock.advance(50); listOf(ev("com.loader")) },
            storm, storm, storm, storm, storm, storm,
        )
        var n = 0
        bridge.treeProvider = { tree(label = "Loading ${n++}") } // never the same twice
        val before = ScreenSignature.of(tree(label = "start"))

        val outcome = ScreenSettle.await(bridge, config, before, clock::now)

        assertFalse(outcome.settled, "a screen still moving must hit the cap")
        assertTrue(outcome.elapsedMs >= config.maxMs)
    }

    @Test
    fun `events from the agent's own overlay are ignored`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(
            clock,
            step { _, _ -> clock.advance(0); emptyList() },
            step { _, _ -> clock.advance(100); listOf(ev("com.aura.aura_ui.feature.debug")) },
            step { t, _ -> clock.advance(t); emptyList() },
        )
        val before = ScreenSignature.of(bridge.treeJson)

        val outcome = ScreenSettle.await(bridge, config, before, clock::now)

        assertTrue(outcome.settled)
        assertEquals(0, outcome.eventCount, "own-overlay events must not count as screen activity")
        assertFalse(outcome.screenChanged)
    }

    @Test
    fun `a tree-blind screen falls back to event activity and says so`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(
            clock,
            step { _, _ -> clock.advance(0); emptyList() },
            step { _, _ -> clock.advance(100); listOf(ev("com.game")) },
            step { t, _ -> clock.advance(t); emptyList() },
        )
        // WebView / Canvas / game: the tree cannot describe the screen at all.
        bridge.treeJson = """{"package_name":"com.game","validation_failed":true,"requires_vision":true}"""
        val before = ScreenSignature.blind("com.game")

        val outcome = ScreenSettle.await(bridge, config, before, clock::now)

        assertTrue(outcome.degradedToEvents, "must admit the verdict is low-confidence")
        assertTrue(outcome.screenChanged, "with a blind tree, event activity is all we have")
    }

    @Test
    fun `with no baseline at all the verdict is marked low-confidence, not asserted`() = runBlocking<Unit> {
        // First action of a session, before anything has looked at the screen. There is
        // nothing to compare against, and asserting "changed" would be a guess — which
        // is exactly what would poison the tap-did-nothing signal.
        val clock = FakeClock()
        val bridge = ScriptedBridge(clock)

        val outcome = ScreenSettle.await(bridge, config, preSignature = null, nowMs = clock::now)

        assertTrue(outcome.degradedToEvents, "no baseline must not masquerade as a confident verdict")
    }

    @Test
    fun `meaningful events are counted apart from ambient noise`() = runBlocking<Unit> {
        val clock = FakeClock()
        val bridge = ScriptedBridge(
            clock,
            step { _, _ -> clock.advance(0); emptyList() },
            step { _, _ ->
                clock.advance(60)
                listOf(ev("com.settings", ScreenActivity.Class.SEMANTIC))
            },
            step { t, _ -> clock.advance(t); emptyList() },
        )
        val before = ScreenSignature.of(bridge.treeJson)

        val outcome = ScreenSettle.await(bridge, config, before, clock::now)

        assertEquals(1, outcome.eventCount)
        assertEquals(1, outcome.meaningfulEventCount, "a toggle flipping is not ambient noise")
    }
}

// ── test doubles ──────────────────────────────────────────────────────────────

internal class FakeClock(private var nowMs: Long = 0L) {
    fun now(): Long = nowMs
    fun advance(ms: Long) { nowMs += ms }
}

private typealias DrainStep = (timeoutMs: Long, maxEvents: Int) -> List<DeviceEvent>

private fun step(s: DrainStep): DrainStep = s

/** A tree with one real, bounded, clickable element — enough to hash non-blind. */
internal fun tree(pkg: String = "com.example", label: String = "Play"): String =
    """
    {"package_name":"$pkg","elements_count":1,"screen_height_px":2000,"elements":[
      {"className":"android.widget.Button","text":"$label","contentDescription":"",
       "isClickable":true,"isEnabled":true,
       "bounds":{"left":10,"top":20,"right":210,"bottom":120}}
    ]}
    """.trimIndent()

internal class ScriptedBridge(
    private val clock: FakeClock,
    vararg steps: (Long, Int) -> List<DeviceEvent>,
) : UiTreeBridge {
    private val queue = ArrayDeque(steps.toList())
    var treeJson: String = tree()

    /** Overrides [treeJson] when set — lets a test return a different tree per read. */
    var treeProvider: (() -> String)? = null

    override suspend fun snapshot(): UiTreeSnapshot =
        UiTreeSnapshot(ok = true, payloadJson = treeProvider?.invoke() ?: treeJson)

    override suspend fun drainEvents(timeoutMs: Long, maxEvents: Int): List<DeviceEvent> {
        val step = queue.removeFirstOrNull() ?: run {
            clock.advance(timeoutMs) // unscripted drain: full timeout, no events
            return emptyList()
        }
        return step(timeoutMs, maxEvents)
    }

}

private fun ev(
    pkg: String,
    cls: ScreenActivity.Class = ScreenActivity.Class.AMBIENT,
) = DeviceEvent(
    type = "TYPE_WINDOW_CONTENT_CHANGED",
    packageName = pkg,
    timestampMs = 0L,
    description = "test",
    changeClass = cls,
)
