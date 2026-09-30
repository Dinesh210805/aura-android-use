package com.aura.aura_ui.agent.ledger

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRunRegistryTest {

    private fun controller(runId: String) = RunLedgerController(
        RunLedger(
            runId = runId,
            goal = "goal for $runId",
            provider = "GROQ",
            modelId = "llama-4-scout",
            startedAtMs = 1_000L,
        ),
    )

    @Test fun `nothing is running before a run registers`() {
        val registry = ActiveRunRegistry()
        assertNull(registry.current)
        assertNull(registry.currentRunId)
        assertFalse(registry.isRunning)
    }

    @Test fun `a registered run is reachable for steering`() {
        val registry = ActiveRunRegistry()
        val c = controller("run-1")
        registry.register("run-1", c)
        assertSame(c, registry.current)
        assertEquals("run-1", registry.currentRunId)
        assertTrue(registry.isRunning)
    }

    @Test fun `unregistering the running run clears the slot`() {
        val registry = ActiveRunRegistry()
        registry.register("run-1", controller("run-1"))
        assertTrue(registry.unregister("run-1"))
        assertNull(registry.current)
        assertFalse(registry.isRunning)
    }

    @Test fun `a newer run takes the slot from an older one`() {
        val registry = ActiveRunRegistry()
        val newer = controller("run-2")
        registry.register("run-1", controller("run-1"))
        registry.register("run-2", newer)
        assertSame(newer, registry.current)
    }

    /**
     * The reason unregister is keyed. If two runs ever overlap (the one-at-a-time guard lives
     * above this layer and cannot see a run started from the overlay's text entry), the older
     * run's teardown must not silently unregister the newer one — that would leave a live run
     * unsteerable with no error raised anywhere.
     */
    @Test fun `a stale teardown cannot unregister the run that replaced it`() {
        val registry = ActiveRunRegistry()
        val newer = controller("run-2")
        registry.register("run-1", controller("run-1"))
        registry.register("run-2", newer)

        assertFalse("stale unregister must be refused", registry.unregister("run-1"))
        assertSame("the newer run must still be steerable", newer, registry.current)
        assertEquals("run-2", registry.currentRunId)
    }

    @Test fun `unregistering when nothing is running is a harmless no-op`() {
        val registry = ActiveRunRegistry()
        assertFalse(registry.unregister("run-1"))
        assertNull(registry.current)
    }

    @Test fun `steering through the registry reaches the run's ledger`() = runTest {
        val registry = ActiveRunRegistry()
        registry.register("run-1", controller("run-1"))

        registry.current?.setDirective("search for food B instead", nowMs = 42L)

        assertEquals("search for food B instead", registry.current?.snapshot()?.directive)
        assertEquals(42L, registry.current?.snapshot()?.directiveAtMs)
    }
}
