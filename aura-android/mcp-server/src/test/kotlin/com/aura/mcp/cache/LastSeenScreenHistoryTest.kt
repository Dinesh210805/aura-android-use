package com.aura.mcp.cache

import com.aura.mcp.server.FakeClock
import com.aura.mcp.server.PostActionObservation
import com.aura.mcp.server.ScreenSettle
import com.aura.mcp.server.ScriptedBridge
import com.aura.mcp.tools.ScreenSignature
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Screen memory — "same screen as N actions ago", matched on layout. */
class LastSeenScreenHistoryTest {

    /** One button; [top] moves it, so different tops are different layouts. */
    private fun screen(top: Int, label: String = "Go"): String =
        """
        {"package_name":"com.example","elements_count":1,"screen_height_px":2000,"elements":[
          {"className":"android.widget.Button","text":"$label","contentDescription":"",
           "isClickable":true,"isEnabled":true,
           "bounds":{"left":10,"top":$top,"right":210,"bottom":${top + 100}}}
        ]}
        """.trimIndent()

    private val a = ScreenSignature.of(screen(top = 20))
    private val b = ScreenSignature.of(screen(top = 600))
    private val c = ScreenSignature.of(screen(top = 1200))

    @Test fun `a screen coming back reports how many actions ago it was on view`() {
        var gen = 0L
        val seen = LastSeenScreen(generation = { gen })
        seen.set(a)
        gen = 1; seen.set(b)
        gen = 2; seen.set(c)
        gen = 3
        assertEquals(3L, seen.actionsSinceSeen(a))
    }

    @Test fun `a match from long ago (an earlier run) stays silent`() {
        var gen = 40L
        val seen = LastSeenScreen(generation = { gen })
        seen.set(a)
        gen = 41; seen.set(b)
        gen = 400
        assertNull(seen.actionsSinceSeen(a))
    }

    @Test fun `the screen already on view is unchanged, not revisited`() {
        var gen = 0L
        val seen = LastSeenScreen(generation = { gen })
        seen.set(a)
        gen = 1
        assertNull(seen.actionsSinceSeen(a))
    }

    @Test fun `a new screen has no history`() {
        val seen = LastSeenScreen(generation = { 5L })
        seen.set(a)
        assertNull(seen.actionsSinceSeen(b))
    }

    @Test fun `changed text on the same layout still counts as the same screen`() {
        var gen = 0L
        val seen = LastSeenScreen(generation = { gen })
        seen.set(ScreenSignature.of(screen(top = 20, label = "3 unread")))
        gen = 1; seen.set(b)
        gen = 2
        assertEquals(2L, seen.actionsSinceSeen(ScreenSignature.of(screen(top = 20, label = "4 unread"))))
    }

    @Test fun `repeat looks refresh the entry instead of stacking`() {
        var gen = 0L
        val seen = LastSeenScreen(generation = { gen })
        seen.set(a)
        gen = 1; seen.set(b)
        gen = 4; seen.set(a) // looked at A again at gen 4
        gen = 5; seen.set(b)
        gen = 6
        assertEquals(2L, seen.actionsSinceSeen(a))
    }

    @Test fun `old screens age out of the ring and blind screens are never stored`() {
        var gen = 0L
        val seen = LastSeenScreen(generation = { gen }, capacity = 2)
        seen.set(a)
        gen = 1; seen.set(b)
        gen = 2; seen.set(ScreenSignature.blind("com.example"))
        gen = 3; seen.set(c) // pushes A out
        gen = 4
        assertNull(seen.actionsSinceSeen(a))
        assertEquals(3L, seen.actionsSinceSeen(b))
    }

    @Test fun `a gesture back to an earlier screen carries seen_before`() = runBlocking<Unit> {
        var gen = 0L
        val clock = FakeClock()
        val bridge = ScriptedBridge(clock)
        val seen = LastSeenScreen(generation = { gen })
        val config = ScreenSettle.Config(noChangeProbeMs = 250, quietMs = 300, maxMs = 1_500)
        val observer = PostActionObservation.observer(bridge, config, clock::now, seen)
        seen.set(a)

        gen = 1; bridge.treeJson = screen(top = 600)
        assertNull(observer.observe("tap")!!["seen_before"])

        gen = 2; bridge.treeJson = screen(top = 20)
        val back = observer.observe("press_back")!!
        assertTrue(back["seen_before"]!!.jsonPrimitive.content.startsWith("Same screen as 2 actions ago"))
    }
}
