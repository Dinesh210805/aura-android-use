package com.aura.aura_ui.agent.conversation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTaskTrackerTest {

    /** Manual clock so throttling is tested by intent, not by sleeping. */
    private var now = 1_000L
    private val tracker = LiveTaskTracker(clock = { now })

    @After fun restoreAsyncDefault() = LiveTaskTracker.resetAsyncSupportForTest()

    // ── in-flight bookkeeping ────────────────────────────────────────────────

    @Test fun `starts empty`() {
        assertFalse(tracker.isRunning)
        assertNull(tracker.inFlight)
    }

    @Test fun `records the call id name and task of a started run`() {
        assertTrue(tracker.onStarted("call-1", "drive_phone", "order my usual"))
        val flight = tracker.inFlight
        assertNotNull(flight)
        assertEquals("call-1", flight!!.callId)
        assertEquals("drive_phone", flight.name)
        assertEquals("order my usual", flight.task)
        assertTrue(tracker.isRunning)
    }

    @Test fun `refuses a second concurrent task`() {
        assertTrue(tracker.onStarted("call-1", "drive_phone", "first"))
        // Two agent runs would fight over the same screen — one phone task at a time.
        assertFalse(tracker.onStarted("call-2", "drive_phone", "second"))
        assertEquals("call-1", tracker.inFlight?.callId)
    }

    @Test fun `finishing clears the slot and returns what was running`() {
        tracker.onStarted("call-1", "drive_phone", "task")
        val finished = tracker.onFinished()
        assertEquals("call-1", finished?.callId)
        assertFalse(tracker.isRunning)
        assertNull(tracker.onFinished())
    }

    @Test fun `a finished task frees the slot for the next one`() {
        tracker.onStarted("call-1", "drive_phone", "first")
        tracker.onFinished()
        assertTrue(tracker.onStarted("call-2", "drive_phone", "second"))
    }

    // ── progress throttling ─────────────────────────────────────────────────

    @Test fun `no progress is emitted when nothing is running`() {
        assertFalse(tracker.shouldEmitProgress("opening Swiggy"))
    }

    @Test fun `the first progress line always passes`() {
        tracker.onStarted("call-1", "drive_phone", "task")
        // This is the line that tells the model the work actually began moving — never throttled.
        assertTrue(tracker.shouldEmitProgress("opening Swiggy"))
    }

    @Test fun `a second line inside the throttle window is dropped`() {
        tracker.onStarted("call-1", "drive_phone", "task")
        assertTrue(tracker.shouldEmitProgress("opening Swiggy"))
        now += LiveTaskTracker.MIN_PROGRESS_INTERVAL_MS - 1
        assertFalse(tracker.shouldEmitProgress("on the cart screen"))
    }

    @Test fun `a line after the throttle window passes`() {
        tracker.onStarted("call-1", "drive_phone", "task")
        tracker.shouldEmitProgress("opening Swiggy")
        now += LiveTaskTracker.MIN_PROGRESS_INTERVAL_MS
        assertTrue(tracker.shouldEmitProgress("on the cart screen"))
    }

    @Test fun `an identical repeat is dropped even after the window`() {
        tracker.onStarted("call-1", "drive_phone", "task")
        tracker.shouldEmitProgress("opening Swiggy")
        now += LiveTaskTracker.MIN_PROGRESS_INTERVAL_MS * 10
        // The agent re-reports the same step while it retries; the model learns nothing from
        // hearing it twice, and it would burn the next throttle slot for a line that is not news.
        assertFalse(tracker.shouldEmitProgress("opening Swiggy"))
    }

    @Test fun `blank progress is never emitted`() {
        tracker.onStarted("call-1", "drive_phone", "task")
        assertFalse(tracker.shouldEmitProgress("   "))
    }

    @Test fun `throttle state resets for a new task`() {
        tracker.onStarted("call-1", "drive_phone", "first")
        assertTrue(tracker.shouldEmitProgress("step one"))
        tracker.onFinished()
        tracker.onStarted("call-2", "drive_phone", "second")
        // A fresh task must be able to report immediately, and even to repeat the previous
        // task's wording — "opening Swiggy" is new information about a different run.
        assertTrue(tracker.shouldEmitProgress("step one"))
    }

    // ── async capability fallback ───────────────────────────────────────────

    @Test fun `async is attempted by default`() {
        // Optimistic on purpose: Google's "not yet supported on 3.1" note may be stale, and the
        // model ids churn. Runtime evidence demotes us, never a doc line.
        assertTrue(LiveTaskTracker.isAsyncSupported)
    }

    @Test fun `demotion is sticky and reported only once`() {
        assertTrue("first demotion reports true", LiveTaskTracker.demoteToBlocking())
        assertFalse(LiveTaskTracker.isAsyncSupported)
        assertFalse("second demotion must not log again", LiveTaskTracker.demoteToBlocking())
    }

    /**
     * Regression: `dispatchToolCall` once decided the async path with a single `&&` chain that ended
     * in `tracker.onStarted(...)`. Kotlin short-circuits, so whenever async support was demoted the
     * side-effecting call never ran — leaving the tracker empty and silently killing the progress
     * feed. This pins the contract that survived the fix: an UNTRACKED task emits no progress, so
     * the blocking fallback can never smuggle an interim response onto a call whose real
     * functionResponse has not been sent yet.
     */
    @Test fun `an untracked task emits no progress`() {
        LiveTaskTracker.demoteToBlocking()
        // Nothing claimed the slot, which is exactly the blocking-fallback state.
        assertFalse(tracker.isRunning)
        assertFalse(tracker.shouldEmitProgress("on the cart screen"))
    }

    @Test fun `progress stops the moment a task finishes`() {
        tracker.onStarted("call-1", "drive_phone", "task")
        assertTrue(tracker.shouldEmitProgress("step one"))
        tracker.onFinished()
        // A late progress callback arriving after the final result must not append to a call the
        // model has already been given an answer for.
        now += LiveTaskTracker.MIN_PROGRESS_INTERVAL_MS * 2
        assertFalse(tracker.shouldEmitProgress("step two"))
    }
}
