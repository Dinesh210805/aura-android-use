package com.aura.aura_ui.agent.ledger

import android.content.Context
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RunLedgerPersisterTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun store(name: String) = RunLedgerStore(EncryptedJsonStore(ctx, name), clock = { 1_000_000L })

    private fun ledger(runId: String, totalSteps: Int) = RunLedger(
        runId = runId,
        goal = "g",
        provider = "GROQ",
        modelId = "llama",
        startedAtMs = 1_000_000L,
        totalSteps = totalSteps,
    )

    @Test fun `rapid mutations coalesce into a single debounced write of the latest`() = runTest {
        val s = store("rlp_coalesce")
        val p = RunLedgerPersister(s, backgroundScope, debounceMs = 500L)

        p.onMutated(ledger("r1", totalSteps = 1))
        p.onMutated(ledger("r1", totalSteps = 2))
        p.onMutated(ledger("r1", totalSteps = 3))
        // Nothing written yet — the debounce window has not elapsed.
        assertNull(s.get("r1"))

        advanceTimeBy(500L)
        runCurrent()
        assertEquals("only the latest snapshot is written", 3, s.get("r1")?.totalSteps)
        assertEquals("no duplicate rows", 1, s.all().size)
    }

    @Test fun `flush writes the latest snapshot immediately, before the debounce elapses`() = runTest {
        val s = store("rlp_flush")
        val p = RunLedgerPersister(s, backgroundScope, debounceMs = 500L)

        p.onMutated(ledger("r1", totalSteps = 1))
        p.onMutated(ledger("r1", totalSteps = 9))
        // The finally-block path: force the pending write to land now (no time advanced).
        p.flush()

        assertEquals(9, s.get("r1")?.totalSteps)
    }

    @Test fun `flush cancels the pending debounce so it does not double-write`() = runTest {
        val s = store("rlp_nodouble")
        val p = RunLedgerPersister(s, backgroundScope, debounceMs = 500L)

        p.onMutated(ledger("r1", totalSteps = 5))
        p.flush()
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(1, s.all().size)
        assertEquals(5, s.get("r1")?.totalSteps)
    }

    @Test fun `flush with nothing pending is a no-op`() = runTest {
        val s = store("rlp_empty")
        val p = RunLedgerPersister(s, backgroundScope, debounceMs = 500L)
        p.flush()
        assertNull(s.get("r1"))
    }
}
