package com.aura.aura_ui.agentcore

import com.aura.mcp.tools.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenWatcherTest {

    private var clock = 10_000L
    private var lastEvent = 0L
    private var screen = 1L
    private var reads = 0
    private val history = ScreenHistory()

    private fun watcher() = ScreenWatcher(
        read = { reads++; ScreenSnapshot.Snapshot("app", "SEEN: app | screen $screen", screen, screen, false) },
        onScreen = { history.passed(it) != null },
        lastEventMs = { lastEvent },
        now = { clock },
    )

    private fun event() {
        lastEvent = clock
    }

    @Test
    fun `the screen the run starts on is recorded once quiet`() = runBlocking {
        val w = watcher()
        w.tick()
        assertEquals(1, reads)
        w.tick()
        assertEquals(1, reads)
    }

    @Test
    fun `a change is read only after 350 ms of quiet`() = runBlocking {
        val w = watcher()
        w.tick()
        screen = 2
        event()
        clock += 100
        w.tick()
        assertEquals(1, reads)
        clock += 300
        w.tick()
        assertEquals(2, reads)
        assertEquals("S2", history.log(null).lines().last().substringBefore(' '))
    }

    @Test
    fun `a screen that never stops moving is still read every 2 s`() = runBlocking {
        val w = watcher()
        w.tick()
        repeat(9) {
            clock += 250
            event()
            w.tick()
        }
        assertEquals(2, reads)
    }

    @Test
    fun `a change back to the same content adds no entry`() = runBlocking {
        val w = watcher()
        w.tick()
        event()
        clock += 400
        w.tick()
        assertEquals(2, reads)
        assertEquals(1, history.log(null).lines().size)
    }
}
