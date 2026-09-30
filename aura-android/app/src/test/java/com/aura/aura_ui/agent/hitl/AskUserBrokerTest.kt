package com.aura.aura_ui.agent.hitl

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AskUserBrokerTest {

    private lateinit var broker: AskUserBroker

    @Before fun fresh() { broker = AskUserBroker() }

    @Test fun `ask publishes the pending question for the UI`() = runTest {
        val job = async { broker.ask("Which size?", listOf("128 GB", "256 GB"), timeoutMs = 5_000) }
        val pending = broker.pending.value ?: run {
            // ask() suspends before we observe — give it a tick
            kotlinx.coroutines.yield()
            broker.pending.value
        }
        checkNotNull(pending)
        assertEquals("Which size?", pending.question)
        assertEquals(listOf("128 GB", "256 GB"), pending.options)
        broker.answer(pending.id, "256 GB")
        assertEquals(AskUserAnswer.Answered("256 GB"), job.await())
    }

    @Test fun `answer resolves ask and clears pending`() = runTest {
        val job = async { broker.ask("Color?", listOf("Black", "White"), timeoutMs = 5_000) }
        kotlinx.coroutines.yield()
        val id = broker.pending.value!!.id
        assertTrue(broker.answer(id, "Black"))
        assertEquals(AskUserAnswer.Answered("Black"), job.await())
        assertNull(broker.pending.value)
    }

    @Test fun `answer with a stale id is rejected`() = runTest {
        val job = async { broker.ask("Color?", emptyList(), timeoutMs = 5_000) }
        kotlinx.coroutines.yield()
        assertFalse(broker.answer("wrong-id", "Black"))
        val id = broker.pending.value!!.id
        broker.answer(id, "White")
        assertEquals(AskUserAnswer.Answered("White"), job.await())
    }

    @Test fun `dismiss resolves ask with Dismissed`() = runTest {
        val job = async { broker.ask("Which one?", listOf("A", "B"), timeoutMs = 5_000) }
        kotlinx.coroutines.yield()
        broker.dismiss(broker.pending.value!!.id)
        assertEquals(AskUserAnswer.Dismissed, job.await())
        assertNull(broker.pending.value)
    }

    @Test fun `ask times out to Timeout and clears pending`() = runTest {
        val result = broker.ask("Anyone there?", emptyList(), timeoutMs = 50)
        assertEquals(AskUserAnswer.Timeout, result)
        assertNull(broker.pending.value)
    }

    @Test fun `a second concurrent ask fails fast instead of clobbering the first`() = runTest {
        val first = async { broker.ask("First?", emptyList(), timeoutMs = 5_000) }
        kotlinx.coroutines.yield()
        val second = broker.ask("Second?", emptyList(), timeoutMs = 5_000)
        assertEquals(AskUserAnswer.Busy, second)
        broker.answer(broker.pending.value!!.id, "ok")
        assertEquals(AskUserAnswer.Answered("ok"), first.await())
    }
}
