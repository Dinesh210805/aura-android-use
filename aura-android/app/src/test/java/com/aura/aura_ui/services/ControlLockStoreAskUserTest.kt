package com.aura.aura_ui.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock must not fire while the agent is waiting on an `ask_user` answer.
 *
 * The user's report, 2026-08-26: *"ask user is triggered but when I try to answer it gets paused,
 * even my tap on a button or other field is mistaken for a pause trigger."* Every touch involved
 * in answering — the option chip, the text field, the keyboard appearing over it — is a human
 * signal, and the lock's whole job is to treat human signals as the human taking the wheel away.
 * While a question is pending that reading is exactly backwards: the run is stopped and waiting,
 * and the touch is the run proceeding.
 */
class ControlLockStoreAskUserTest {

    private fun store(awaiting: () -> Boolean) = ControlLockStore(
        clock = { 10_000L },
        awaitingUserAnswer = awaiting,
    ).also { it.noteLocalRunStarted() }

    @Test
    fun `a human signal pauses a driving run when no question is pending`() {
        val lock = store(awaiting = { false })
        lock.onObservedEvent(isHumanSignal = true)
        assertTrue("the ordinary case must still pause", lock.isPausedByHuman)
    }

    @Test
    fun `answering a pending question does not pause the run`() {
        val lock = store(awaiting = { true })
        lock.onObservedEvent(isHumanSignal = true)
        assertFalse("answering must not be read as taking the wheel", lock.isPausedByHuman)
    }

    /**
     * Answering involves several touches — the field, the keyboard, the send button. One suppressed
     * event is not enough; the suppression has to hold for as long as the question does.
     */
    @Test
    fun `every touch while a question is pending is suppressed`() {
        val lock = store(awaiting = { true })
        repeat(6) { lock.onObservedEvent(isHumanSignal = true) }
        assertFalse(lock.isPausedByHuman)
    }

    /** Once the question resolves, the lock goes straight back to protecting the user. */
    @Test
    fun `the lock re-arms as soon as the question is answered`() {
        var pending = true
        val lock = store(awaiting = { pending })
        lock.onObservedEvent(isHumanSignal = true)
        assertFalse(lock.isPausedByHuman)

        pending = false
        lock.onObservedEvent(isHumanSignal = true)
        assertTrue("suppression must not outlive the question", lock.isPausedByHuman)
    }
}
