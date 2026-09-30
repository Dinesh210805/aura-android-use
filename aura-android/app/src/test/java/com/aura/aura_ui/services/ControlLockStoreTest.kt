package com.aura.aura_ui.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 2026-07-31 — *one lock, three holders*, app-side state.
 *
 * This holds one bit: does the human have the wheel? The decision logic lives in
 * [SelfActionWindow] (is this event ours?) and `ControlLock` (what does a holder mean?);
 * this class only remembers, so that the accessibility thread and the MCP dispatch
 * threads are looking at the same answer.
 *
 * The clock is injected so windows can be exercised without sleeping. A test that sleeps
 * 30 s to prove a timeout is a test nobody runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlLockStoreTest {

    private class FakeClock(var now: Long = 1_000) : () -> Long {
        override fun invoke(): Long = now
    }

    /**
     * A store with a driver already holding the wheel.
     *
     * Most of these tests are about *what happens when the human interrupts*, which
     * presupposes something to interrupt. Since 2026-08-03 the trigger is only armed while
     * somebody is driving, so a bare store ignores events entirely — see
     * [an idle phone cannot be paused by ordinary use].
     */
    private fun drivingStore(clock: FakeClock, scope: TestScope? = null): ControlLockStore {
        val store = if (scope == null) ControlLockStore(clock) else ControlLockStore(clock, scope)
        store.noteLocalRunStarted()
        return store
    }

    @Test
    fun `nobody holds the lock to begin with`() {
        assertFalse(ControlLockStore(FakeClock()).isPausedByHuman)
    }

    @Test
    fun `an unexplained event during a run hands the lock to the human`() {
        val store = drivingStore(FakeClock())

        store.onObservedEvent()

        assertTrue(store.isPausedByHuman)
    }

    @Test
    fun `AURA's own gesture does not pause AURA`() {
        // The single most important case. Get this wrong and the agent pauses itself on
        // every action it takes, and the product does nothing at all.
        val clock = FakeClock()
        val store = drivingStore(clock)

        store.noteDispatch()
        clock.now += 100

        store.onObservedEvent()

        assertFalse("AURA paused on its own tap", store.isPausedByHuman)
    }

    @Test
    fun `AURA's own scroll does not pause AURA while the fling settles`() {
        // The reported bug. A scroll's echo outlives a tap's by seconds, so attributing it
        // with a tap-sized window let AURA's own fling read as a human flicking the screen.
        val clock = FakeClock()
        val store = drivingStore(clock)

        store.noteDispatch(SelfActionWindow.ActionKind.SCROLL)
        clock.now += SelfActionWindow.ActionKind.TAP.settleMs + 500

        store.onObservedEvent()

        assertFalse("AURA paused on its own scrolling", store.isPausedByHuman)
    }

    @Test
    fun `a touch after AURA's gesture has settled does pause`() {
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.noteDispatch()

        clock.now += SelfActionWindow.ActionKind.TAP.settleMs + 1
        store.onObservedEvent()

        assertTrue(store.isPausedByHuman)
    }

    @Test
    fun `resuming gives the lock back`() {
        val store = drivingStore(FakeClock())
        store.onObservedEvent()

        store.resume()

        assertFalse(store.isPausedByHuman)
    }

    @Test
    fun `a later self-caused event cannot silently un-pause`() {
        // Once the human has the wheel, only an explicit resume takes it back. If a stray
        // event could clear the pause, the agent would quietly start driving again while
        // the user was still typing.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.onObservedEvent()

        store.noteDispatch()
        clock.now += 10
        store.onObservedEvent()

        assertTrue(store.isPausedByHuman)
    }

    // ── Arming: the lock only means something while somebody drives ──────────

    @Test
    fun `an idle phone cannot be paused by ordinary use`() {
        // THE headline bug: "I open the overlay and it says paused." A person using their
        // phone emits VIEW_CLICKED every few seconds; each one re-armed the idle countdown,
        // so the lock engaged during ordinary use and the 30 s release could never fire.
        // By the time the user opened AURA to ask for something, AURA was already paused —
        // on nothing at all.
        val store = ControlLockStore(FakeClock())

        repeat(10) { store.onObservedEvent() }

        assertFalse("an idle AURA paused itself on the user living their life", store.isPausedByHuman)
    }

    @Test
    fun `a finished run disarms the trigger`() {
        val store = drivingStore(FakeClock())
        store.noteLocalRunFinished()

        store.onObservedEvent()

        assertFalse(store.isPausedByHuman)
    }

    @Test
    fun `a remote MCP client counts as driving`() {
        // The case the spec cared about most: the user's PC keeps firing tool calls into a
        // phone they just picked up. There is no local run here at all.
        val store = ControlLockStore(FakeClock())
        store.noteRemoteToolCall()

        store.onObservedEvent()

        assertTrue(store.isPausedByHuman)
    }

    @Test
    fun `a remote client that stopped calling stops counting as driving`() {
        val clock = FakeClock()
        val store = ControlLockStore(clock)
        store.noteRemoteToolCall()

        clock.now += ControlLockStore.REMOTE_DRIVING_WINDOW_MS
        store.onObservedEvent()

        assertFalse(store.isPausedByHuman)
    }

    @Test
    fun `speaking pause takes the lock even with nobody driving`() {
        // Arming gates the *inferred* trigger, never an explicit instruction. Saying "stop"
        // is never ambient noise, and it must work before a run has started — that is how
        // a user stops something they can see is about to go wrong.
        val store = ControlLockStore(FakeClock())

        store.pause()

        assertTrue(store.isPausedByHuman)
    }

    // ── Releasing ────────────────────────────────────────────────────────────

    @Test
    fun `a pause releases itself once the human has clearly stopped`() {
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.onObservedEvent()
        assertTrue(store.isPausedByHuman)

        clock.now += ControlLockStore.AUTO_RESUME_AFTER_MS

        assertFalse("the lock latched with no way back", store.isPausedByHuman)
    }

    @Test
    fun `an explicit pause never expires on its own`() {
        // The headline risk of the on-screen Pause button (2026-09-07). The idle release
        // exists to expire a pause *inferred* from a touch: the user grabbed their phone,
        // and thirty silent seconds later they evidently do not care any more. Pressing
        // Pause is the opposite — putting the phone down is the whole point of pressing it,
        // and resuming underneath the user is exactly the betrayal this lock prevents.
        val clock = FakeClock()
        val scope = TestScope()
        val store = drivingStore(clock, scope)
        store.pause()

        clock.now += ControlLockStore.AUTO_RESUME_AFTER_MS * 10
        scope.advanceTimeBy(ControlLockStore.AUTO_RESUME_AFTER_MS * 10 + 1)
        scope.runCurrent()

        assertTrue("the agent resumed behind the user's back", store.isPausedByHuman)
        assertTrue("the Pause button would have flipped back on its own", store.pausedByHuman.value)
    }

    @Test
    fun `resuming after an explicit pause re-arms the ordinary idle release`() {
        // The explicit flag must not be sticky: once the user resumes, a LATER pause
        // inferred from a touch has to expire normally again.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.pause()
        store.resume()

        clock.now += ControlLockStore.RESUME_GRACE_MS
        store.onObservedEvent()
        assertTrue(store.isPausedByHuman)

        clock.now += ControlLockStore.AUTO_RESUME_AFTER_MS

        assertFalse("an explicit pause permanently disabled the idle release", store.isPausedByHuman)
    }

    @Test
    fun `the run controls appear and disappear with a local run`() {
        // What the bottom-centre Pause button binds to. Not isDriving: that also covers a
        // remote MCP client, which has no finished event and so could never turn the
        // controls back off.
        val store = ControlLockStore(FakeClock())
        assertFalse(store.localRunning.value)

        store.noteLocalRunStarted()
        assertTrue(store.localRunning.value)

        store.noteLocalRunFinished()
        assertFalse("the controls would outlive the run", store.localRunning.value)
    }

    @Test
    fun `a pause holds while the human is still active`() {
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.onObservedEvent()

        clock.now += ControlLockStore.AUTO_RESUME_AFTER_MS - 1

        assertTrue(store.isPausedByHuman)
    }

    @Test
    fun `each fresh human signal extends the pause`() {
        // Someone using their phone for two minutes must not have AURA resume underneath
        // them at the thirty-second mark. The countdown restarts on every touch.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.onObservedEvent()

        repeat(5) {
            clock.now += ControlLockStore.AUTO_RESUME_AFTER_MS - 1
            assertTrue(store.isPausedByHuman)
            store.onObservedEvent()
        }

        assertTrue(store.isPausedByHuman)
    }

    @Test
    fun `the pause expires on its own, with nobody reading the getter`() {
        // The other half of "it says paused". The Pause pill's visibility comes from the
        // StateFlow, but the release used to live ONLY inside the isPausedByHuman getter —
        // and while paused every tool call is refused, so the agent stops calling tools and
        // nothing reads it. The pill therefore stayed on screen indefinitely after the user
        // put the phone down: an expiry that structurally could not run when it was needed.
        //
        // Note this test never touches isPausedByHuman. That is the point.
        val clock = FakeClock()
        val scope = TestScope()
        val store = drivingStore(clock, scope)
        store.onObservedEvent()
        assertTrue(store.pausedByHuman.value)

        clock.now += ControlLockStore.AUTO_RESUME_AFTER_MS
        scope.advanceTimeBy(ControlLockStore.AUTO_RESUME_AFTER_MS + 1)
        scope.runCurrent()

        assertFalse("the Pause pill would still be on screen", store.pausedByHuman.value)
    }

    @Test
    fun `an expired pause is released for observers too and not just readers`() {
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.onObservedEvent()

        clock.now += ControlLockStore.AUTO_RESUME_AFTER_MS
        store.isPausedByHuman

        assertFalse(store.pausedByHuman.value)
    }

    @Test
    fun `the pause is observable so the UI can react`() {
        // The spec requires the status pill to HIDE and the Pause pill to appear the
        // moment this flips. Polling a boolean would make that lag visibly.
        val store = drivingStore(FakeClock())

        store.onObservedEvent()

        assertTrue(store.pausedByHuman.value)
    }

    @Test
    fun `an explicit resume survives a screen that keeps emitting events`() {
        // Without a grace period an auto-advancing carousel re-pauses the instant the user
        // taps Resume, and there is no way out at all: every tool call is refused, so the
        // agent cannot even leave the screen causing it.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.onObservedEvent()

        store.resume()
        clock.now += ControlLockStore.RESUME_GRACE_MS - 1
        store.onObservedEvent()

        assertFalse(store.isPausedByHuman)
    }

    // ── The paused task ──────────────────────────────────────────────────────

    @Test
    fun `the active task is remembered so a refusal can name it`() {
        val store = ControlLockStore(FakeClock())
        store.noteTaskStarted("messaging mum")

        assertEquals("messaging mum", store.activeTask)
    }

    @Test
    fun `dropping the task clears it and hands the wheel back`() {
        val store = ControlLockStore(FakeClock())
        store.noteTaskStarted("messaging mum")
        store.pause()

        store.dropTask()

        assertNull(store.activeTask)
        assertFalse(store.isPausedByHuman)
    }

    // ── Chained attribution at store level (2026-08-05) ──────────────────────

    @Test
    fun `a tap whose screen keeps repainting does not pause the agent`() {
        // The 2026-08-05 device regression. Tap Save at t=1000; the new screen paints for
        // ~2.5s emitting events every 300ms. Every one of those was read as a human, so
        // the three end_session calls that followed were all refused.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.noteDispatch(SelfActionWindow.ActionKind.TAP)

        for (t in longArrayOf(1_300, 1_600, 1_900, 2_200, 2_500, 2_800, 3_100, 3_400)) {
            clock.now = t
            store.onObservedEvent()
        }

        assertFalse("AURA paused itself on its own tap's repaint", store.isPausedByHuman)
    }

    @Test
    fun `a real touch after the screen settles still pauses the agent`() {
        // The invariant that must survive the fix: ties break toward the human. The gap
        // has to clear TAP's own 800ms grace as well as the quiet window, or the grace is
        // what is being tested rather than the chain.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.noteDispatch(SelfActionWindow.ActionKind.TAP)

        for (t in longArrayOf(1_300, 1_600)) {
            clock.now = t
            store.onObservedEvent()          // AURA's own repaint — chains
        }
        clock.now = 1_600 + SelfActionWindow.QUIET_GAP_MS + 1
        store.onObservedEvent()              // device went quiet, then something happened

        assertTrue("a genuine touch was swallowed", store.isPausedByHuman)
    }

    @Test
    fun `the real WhatsApp new-group tap, replayed from device timings, does not pause`() {
        // Not a synthetic sequence — these are the sinceDispatch values ControlLockDiagnostics
        // recorded on CPH2661 (2026-08-05) after AURA tapped WhatsApp's new-group button, up
        // to and including the SCROLLED event that paused the agent.
        //
        // Largest gap between consecutive effects: 236ms, well inside QUIET_GAP_MS. The first
        // lands at 500ms, inside TAP's 800ms grace. So the chain should carry unbroken — and
        // it did not, because only the human-signal events were reaching the store at all.
        val ambient = longArrayOf(
            500, 500, 500, 519, 526, 526, 624, 770, 819, 835, 1_071, 1_071, 1_071, 1_286, 1_287, 1_352,
        )
        val scrolledAt = 1_359L   // the event that paused it

        val clock = FakeClock()
        val store = drivingStore(clock)
        val base = clock.now
        store.noteDispatch(SelfActionWindow.ActionKind.TAP)

        ambient.forEach { offset ->
            clock.now = base + offset
            // CONTENT_CHANGED / WINDOWS_CHANGED / WINDOW_STATE / FOCUSED — none are human
            // signals, but every one is proof the screen is still repainting.
            store.onObservedEvent(isHumanSignal = false)
        }
        clock.now = base + scrolledAt
        store.onObservedEvent(isHumanSignal = true)

        assertFalse("AURA paused on its own new-group tap", store.isPausedByHuman)
    }

    @Test
    fun `ambient churn alone can never pause the agent`() {
        // The other direction: a non-human event that is ALSO not AURA's echo — a background
        // app repainting long after the last action — must do nothing at all. Without this,
        // widening the call site to every event would turn ordinary system noise into pauses.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.noteDispatch(SelfActionWindow.ActionKind.TAP)

        clock.now += 60_000     // far outside any window
        store.onObservedEvent(isHumanSignal = false)

        assertFalse("ambient churn paused the agent", store.isPausedByHuman)
    }

    // ── Real touch signal (2026-08-05) ───────────────────────────────────────

    @Test
    fun `a real finger on the glass pauses the agent`() {
        // The whole point of the touch overlay: this is an actual hardware touch reported
        // by FLAG_WATCH_OUTSIDE_TOUCH, not an accessibility event we have to guess about.
        // No inference, no timing window, no "is a settling list a person?".
        val clock = FakeClock()
        val store = drivingStore(clock)

        store.onHumanTouch()

        assertTrue(store.isPausedByHuman)
    }

    @Test
    fun `a touch AURA itself injected does not pause it`() {
        // dispatchGesture injects at the input layer, so AURA's own taps reach the overlay
        // as touches too. Unlike the accessibility-event path this is easy to attribute:
        // an injected touch lands AT dispatch time, not 1400ms later during a repaint.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.noteDispatch(SelfActionWindow.ActionKind.TAP)

        clock.now += 30      // the stroke itself
        store.onHumanTouch()

        assertFalse("AURA paused on its own injected tap", store.isPausedByHuman)
    }

    @Test
    fun `a touch while nothing is driving is just somebody using their phone`() {
        val clock = FakeClock()
        val store = ControlLockStore(clock)   // deliberately NOT driving

        store.onHumanTouch()

        assertFalse(store.isPausedByHuman)
    }

    @Test
    fun `a touch well after AURA's action is a person, however long the screen churns`() {
        // The contrast with the event path that caused 13 false pauses: a genuine touch
        // 3 seconds after a tap pauses immediately. There is no repaint to confuse it with,
        // because a repaint does not produce a touch.
        val clock = FakeClock()
        val store = drivingStore(clock)
        store.noteDispatch(SelfActionWindow.ActionKind.TAP)

        clock.now += 3_000
        store.onHumanTouch()

        assertTrue(store.isPausedByHuman)
    }

    @Test
    fun `a finished run leaves no task behind to be paused on`() {
        // A completed task is not a paused task. Leaving it set made the next refusal name
        // work that had already finished — "I'm paused on messaging mum" long after mum
        // had been messaged.
        val store = ControlLockStore(FakeClock())
        store.noteLocalRunStarted()
        store.noteTaskStarted("messaging mum")

        store.noteLocalRunFinished()

        assertNull(store.activeTask)
    }
}
