package com.aura.aura_ui.agent.hitl

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12 — the hook chain's Confirm decisions bridge to the ask_user HITL plane.
 * Yes-only opt-in: anything but an explicit "Yes" answer reads as "no".
 */
class ConfirmViaAskUserTest {

    @Test fun `answering Yes confirms`() = runTest {
        val broker = AskUserBroker()
        val confirm = confirmViaAskUser(broker, timeoutMs = 5_000)
        val decision = async { confirm("Run a destructive action (notification_action)?") }
        val q = broker.pending.first { it != null }!!
        assertTrue(q.options.contains("Yes") && q.options.contains("No"))
        broker.answer(q.id, "Yes")
        assertTrue(decision.await())
    }

    @Test fun `answering No declines`() = runTest {
        val broker = AskUserBroker()
        val confirm = confirmViaAskUser(broker, timeoutMs = 5_000)
        val decision = async { confirm("sure?") }
        val q = broker.pending.first { it != null }!!
        broker.answer(q.id, "No")
        assertFalse(decision.await())
    }

    @Test fun `a free-text yes also confirms`() = runTest {
        val broker = AskUserBroker()
        val confirm = confirmViaAskUser(broker, timeoutMs = 5_000)
        val decision = async { confirm("sure?") }
        val q = broker.pending.first { it != null }!!
        broker.answer(q.id, " yes ")
        assertTrue(decision.await())
    }

    @Test fun `dismissal declines`() = runTest {
        val broker = AskUserBroker()
        val confirm = confirmViaAskUser(broker, timeoutMs = 5_000)
        val decision = async { confirm("sure?") }
        val q = broker.pending.first { it != null }!!
        broker.dismiss(q.id)
        assertFalse(decision.await())
    }
}
