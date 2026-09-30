package com.aura.aura_ui.agent.strategy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-08-05 device traces showed what happens when a refusal looks retryable.
 *
 * `ControlLock`'s message told the model to "wait and try again in a moment", and the model
 * did exactly that: five identical `get_ui_tree` calls, then four `end_session` attempts with
 * the wording changed each time — as if the phrasing were what got refused. Every one was a
 * full ~20k-token round trip against a free-tier quota.
 *
 * A blocked loop has nothing useful to think about. Waiting is cheaper and truer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PausedLoopGateTest {

    @Test
    fun `returns immediately when nothing holds the lock`() = runTest {
        val gate = PausedLoopGate(isPaused = { false })
        assertTrue(gate.awaitResume())
    }

    @Test
    fun `waits until the human hands the wheel back`() = runTest {
        // Paused for the first three polls, then released. runTest's virtual clock makes
        // the timing deterministic — no real waiting.
        var polls = 0
        val gate = PausedLoopGate(
            isPaused = { polls++ < 3 },
            pollMs = 100,
            maxWaitMs = 10_000,
        )
        assertTrue("the gate gave up on a lock that did clear", gate.awaitResume())
        assertTrue("the gate returned without ever waiting", polls > 1)
    }

    @Test
    fun `gives up after the bound so a stuck lock cannot hang the run forever`() = runTest {
        // ControlLockStore.AUTO_RESUME_AFTER_MS already releases a stale pause, so a wait
        // longer than that means something is wrong and the run should end honestly rather
        // than hang with a frozen phone and no explanation.
        val gate = PausedLoopGate(isPaused = { true }, pollMs = 100, maxWaitMs = 1_000)
        assertFalse(gate.awaitResume())
    }
}
