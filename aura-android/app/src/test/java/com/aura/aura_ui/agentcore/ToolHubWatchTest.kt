package com.aura.aura_ui.agentcore

import ai.koog.agents.core.tools.ToolDescriptor
import com.aura.aura_ui.uistream.ScreenIdle
import com.aura.mcp.cache.ScreenActivity
import com.aura.mcp.tools.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The screen watcher starts with a turn's first phone call and stops with the turn. */
class ToolHubWatchTest {

    private val reads = AtomicInteger()
    private var screen = 1L

    private val phone = object : ToolSource {
        override val id = "phone"
        override suspend fun list() = listOf(
            ToolSpec(ToolDescriptor("read_screen", "Look.", emptyList(), emptyList()), ToolRisk.READ, ToolOrigin.PHONE, ToolHub.SCREEN, Origin.SCREEN),
        )
        override suspend fun call(name: String, args: JsonObject, turn: TurnState) = SourceResult("SEEN: app | Home\nSCREEN 1x1\n| 1 Home |")
    }

    private fun hub() = ToolHub(
        sources = { listOf(phone) },
        broker = UserBroker(),
        snapshot = {
            reads.incrementAndGet()
            ScreenSnapshot.Snapshot("app", "SEEN: app | screen $screen", screen, screen, false)
        },
    )

    @Test
    fun `no phone call means no watcher, the first one starts it and the turn's end stops it`() = runBlocking {
        val hub = hub().apply { refresh() }
        val turn = TurnState("t", RunMode.FOREGROUND, sink = {})
        delay(600)
        assertEquals(0, reads.get())

        hub.observeScreen(turn)
        delay(600)
        assertTrue("the look and the watcher both read", reads.get() >= 2)
        assertEquals("S1", turn.screens.log(null).lines().single().substringBefore(' '))

        turn.stopWatching()
        delay(300)
        val after = reads.get()
        screen = 2
        // A real screen change after the turn: a running watcher would read it within 600 ms.
        ScreenIdle.onEvent(ScreenActivity.Class.STRUCTURAL, System.currentTimeMillis())
        delay(600)
        assertEquals(after, reads.get())
    }
}
