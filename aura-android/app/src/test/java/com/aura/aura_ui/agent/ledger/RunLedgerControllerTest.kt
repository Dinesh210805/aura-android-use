package com.aura.aura_ui.agent.ledger

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLedgerControllerTest {

    private fun newController(onMutated: (RunLedger) -> Unit = {}) = RunLedgerController(
        RunLedger(
            runId = "123-abc",
            goal = "add iphone to cart on amazon",
            provider = "GROQ",
            modelId = "llama-4-scout",
            startedAtMs = 1_000L,
        ),
        onMutated = onMutated,
    )

    // ── Plan checklist (set_plan / mark_step) ──

    @Test fun `setPlan replaces the checklist and bumps the plan version`() = runTest {
        val c = newController()
        c.setPlan(listOf("open amazon", "search iphone", "add to cart"))
        c.setPlan(listOf("open amazon", "use deeplink instead"))
        val ledger = c.snapshot()
        assertEquals(2, ledger.planVersion)
        assertEquals(listOf("open amazon", "use deeplink instead"), ledger.planSteps.map { it.text })
        assertTrue(ledger.planSteps.all { it.status == PlanStepStatus.PENDING })
    }

    @Test fun `markStep updates one step's status`() = runTest {
        val c = newController()
        c.setPlan(listOf("a", "b", "c"))
        c.markStep(0, PlanStepStatus.DONE)
        c.markStep(1, PlanStepStatus.IN_PROGRESS)
        val steps = c.snapshot().planSteps
        assertEquals(PlanStepStatus.DONE, steps[0].status)
        assertEquals(PlanStepStatus.IN_PROGRESS, steps[1].status)
        assertEquals(PlanStepStatus.PENDING, steps[2].status)
    }

    @Test fun `markStep with an out-of-range index is a safe no-op`() = runTest {
        val c = newController()
        c.setPlan(listOf("a"))
        c.markStep(5, PlanStepStatus.DONE)
        c.markStep(-1, PlanStepStatus.DONE)
        assertEquals(PlanStepStatus.PENDING, c.snapshot().planSteps[0].status)
    }

    @Test fun `markStep note lands in facts`() = runTest {
        val c = newController()
        c.setPlan(listOf("search iphone"))
        c.markStep(0, PlanStepStatus.DONE, note = "price shown is ₹134,900")
        assertTrue(c.snapshot().facts.any { it.contains("134,900") })
    }

    // ── Facts (model-noted, capped) ──

    @Test fun `facts are capped and the oldest is dropped first`() = runTest {
        val c = newController()
        repeat(RunLedgerCaps.MAX_FACTS + 2) { i -> c.noteFact("fact $i") }
        val facts = c.snapshot().facts
        assertEquals(RunLedgerCaps.MAX_FACTS, facts.size)
        assertEquals("fact 2", facts.first())
        assertEquals("fact ${RunLedgerCaps.MAX_FACTS + 1}", facts.last())
    }

    @Test fun `an overlong fact is truncated`() = runTest {
        val c = newController()
        c.noteFact("x".repeat(500))
        assertEquals(RunLedgerCaps.MAX_FACT_CHARS, c.snapshot().facts.single().length)
    }

    @Test fun `blank facts are ignored`() = runTest {
        val c = newController()
        c.noteFact("   ")
        assertTrue(c.snapshot().facts.isEmpty())
    }

    // ── Step records (hook-driven) ──

    @Test fun `recordToolStep keeps a rolling window and full counts`() = runTest {
        val c = newController()
        repeat(RunLedgerCaps.MAX_RECENT_STEPS + 5) { i ->
            c.recordToolStep(tool = "tap", label = "btn $i", ok = true, screenChanged = true)
        }
        c.recordToolStep(tool = "tap", label = "broken", ok = false, screenChanged = null)
        val ledger = c.snapshot()
        assertEquals(RunLedgerCaps.MAX_RECENT_STEPS, ledger.recentSteps.size)
        assertEquals("broken", ledger.recentSteps.last().label)
        assertEquals(RunLedgerCaps.MAX_RECENT_STEPS + 6, ledger.totalSteps)
        assertEquals(1, ledger.failedSteps)
    }

    // ── Dead ends / constraints ──

    @Test fun `recordDeadEnd dedupes and caps`() = runTest {
        val c = newController()
        c.recordDeadEnd("search bar leads nowhere")
        c.recordDeadEnd("search bar leads nowhere")
        assertEquals(1, c.snapshot().deadEnds.size)
        repeat(RunLedgerCaps.MAX_DEAD_ENDS + 3) { i -> c.recordDeadEnd("dead end $i") }
        assertEquals(RunLedgerCaps.MAX_DEAD_ENDS, c.snapshot().deadEnds.size)
    }

    // ── Lifecycle ──

    @Test fun `finalize stamps end time and reason`() = runTest {
        val c = newController()
        assertNull(c.snapshot().endedAtMs)
        c.finalize("completed", nowMs = 2_000L)
        val ledger = c.snapshot()
        assertEquals("completed", ledger.endReason)
        assertEquals(2_000L, ledger.endedAtMs)
    }

    @Test fun `every mutation notifies onMutated with the fresh snapshot`() = runTest {
        val seen = mutableListOf<RunLedger>()
        val c = newController(onMutated = { seen.add(it) })
        c.setPlan(listOf("a"))
        c.markStep(0, PlanStepStatus.DONE)
        c.noteFact("f")
        c.recordToolStep("tap", "x", ok = true, screenChanged = true)
        c.recordDeadEnd("d")
        c.finalize("failed", nowMs = 3L)
        assertEquals(6, seen.size)
        assertEquals("failed", seen.last().endReason)
        assertEquals(1, seen.last().planSteps.size)
    }

    // ── Voice steering: directive + retarget ──

    @Test fun `setDirective replaces rather than accumulating`() = runTest {
        val c = newController()
        c.setDirective("search for food A instead", nowMs = 10L)
        c.setDirective("no, food B", nowMs = 20L)
        assertEquals("no, food B", c.snapshot().directive)
        assertEquals(20L, c.snapshot().directiveAtMs)
    }

    @Test fun `a blank directive clears the standing instruction`() = runTest {
        val c = newController()
        c.setDirective("do it differently", nowMs = 10L)
        c.setDirective("   ", nowMs = 20L)
        assertNull(c.snapshot().directive)
        assertNull(c.snapshot().directiveAtMs)
    }

    @Test fun `a directive is capped so one long ramble cannot swamp the block`() = runTest {
        val c = newController()
        c.setDirective("z".repeat(RunLedgerCaps.MAX_DIRECTIVE_CHARS + 200))
        assertEquals(RunLedgerCaps.MAX_DIRECTIVE_CHARS, c.snapshot().directive?.length)
    }

    @Test fun `retarget changes the goal and clears the plan built for the old one`() = runTest {
        val c = newController()
        c.setPlan(listOf("open swiggy", "search food A", "add to cart"))
        c.retarget("order food B instead")
        val l = c.snapshot()
        assertEquals("order food B instead", l.goal)
        assertTrue("stale checklist must not survive a retarget", l.planSteps.isEmpty())
        assertEquals("plan version must move so the change is visible", 2, l.planVersion)
    }

    @Test fun `a blank retarget is ignored so a bad parse cannot erase the goal`() = runTest {
        val c = newController()
        c.setPlan(listOf("step"))
        c.retarget("  ")
        assertEquals("add iphone to cart on amazon", c.snapshot().goal)
        assertEquals(1, c.snapshot().planSteps.size)
    }

    @Test fun `finalize drops the directive so a resume cannot obey a stale instruction`() = runTest {
        val c = newController()
        c.setDirective("switch to food B", nowMs = 10L)
        c.finalize("cancelled", nowMs = 20L)
        assertNull(c.snapshot().directive)
        assertNull(c.snapshot().directiveAtMs)
        assertEquals("cancelled", c.snapshot().endReason)
    }

    // ── Persistence shape ──

    @Test fun `ledger round-trips through kotlinx json`() = runTest {
        val c = newController()
        c.setPlan(listOf("a", "b"))
        c.markStep(0, PlanStepStatus.DONE, note = "note")
        c.recordToolStep("tap", "Compose", ok = true, screenChanged = true)
        c.recordDeadEnd("dead")
        val original = c.snapshot()
        val decoded = Json.decodeFromString<RunLedger>(Json.encodeToString(RunLedger.serializer(), original))
        assertEquals(original, decoded)
        assertNotNull(decoded.planSteps)
    }
}
