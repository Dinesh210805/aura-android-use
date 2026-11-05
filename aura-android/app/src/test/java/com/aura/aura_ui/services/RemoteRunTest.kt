package com.aura.aura_ui.services

import com.aura.aura_ui.services.RemoteRun.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRunTest {

    private var now = 1_000L
    private var paused = false
    private val beats = mutableListOf<AgentStatusRegistry.Beat>()
    private val run = RemoteRun(clock = { now }, isPaused = { paused }, publish = { beats += it })

    @Test
    fun `first call starts a run, end_session ends it with the client's report`() {
        assertTrue(run.onToolStart("read_screen", null, "Laptop / Claude"))
        assertFalse(run.onToolStart("tap", """{"som_id":3}""", "Laptop / Claude"))
        run.onToolEnd("tap", true, """{"success":true,"label":"Search"}""")
        assertTrue(run.active.value)
        assertEquals("Laptop / Claude · step 1", beats.last().headline)

        now += 30_000
        run.onToolStart("end_session", null, "Laptop / Claude")
        run.onEndSession("Found it", "partial", "## Pick\n- one")
        run.onToolEnd("end_session", true, "{}")

        assertFalse(run.active.value)
        val report = run.report.value!!
        assertEquals(Outcome.PARTIAL, report.outcome)
        assertEquals("Found it", report.summary)
        assertEquals("## Pick\n- one", report.markdown)
        assertEquals(1, report.steps)
        assertEquals(30_000L, report.durationMs)
    }

    @Test
    fun `the next call clears a report so it never covers a live run`() {
        run.onToolStart("tap", null, null)
        run.onEndSession("agent-end", null, null)
        assertEquals(Outcome.DONE, run.report.value!!.outcome)
        assertNull(run.report.value!!.summary)
        run.onToolStart("read_screen", null, null)
        assertNull(run.report.value)
    }

    @Test
    fun `idle ends a run, but never while the human holds the lock`() {
        run.onToolStart("tap", null, null)
        now += RemoteRun.IDLE_END_MS
        paused = true
        run.checkIdle()
        assertTrue(run.active.value)
        paused = false
        run.checkIdle()
        assertEquals(Outcome.WENT_QUIET, run.report.value!!.outcome)
    }

    @Test
    fun `cancel refuses a client that keeps calling, and lets a quiet one back`() {
        run.onToolStart("tap", null, null)
        run.stopByUser()
        assertEquals(Outcome.STOPPED, run.report.value!!.outcome)
        assertTrue(run.isRefusing)

        now += RemoteRun.STOP_QUIET_MS - 1
        run.onToolRefused()
        assertTrue(run.isRefusing)

        now += RemoteRun.STOP_QUIET_MS
        assertFalse(run.isRefusing)
    }

    @Test
    fun `a client that never stops retrying is let go at the cap`() {
        run.onToolStart("tap", null, null)
        run.stopByUser()
        while (now < 1_000L + RemoteRun.STOP_CAP_MS) {
            now += 10_000
            run.onToolRefused()
        }
        assertFalse(run.isRefusing)
    }

    @Test
    fun `pause shows progress and resume takes it down`() {
        run.onToolStart("tap", null, null)
        run.onToolEnd("tap", false, """{"error":"stale"}""")
        run.notePaused()
        assertEquals(Outcome.PAUSED, run.report.value!!.outcome)
        assertEquals(1, run.report.value!!.recent.size)
        run.noteResumed()
        assertNull(run.report.value)
    }
}
