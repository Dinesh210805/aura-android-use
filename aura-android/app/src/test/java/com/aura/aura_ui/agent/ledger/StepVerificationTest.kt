package com.aura.aura_ui.agent.ledger

import com.aura.aura_ui.agent.proof.CompletionGate
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step-verification path (spec 2026-09-24): `mark_step done` goes through the checker, three
 * rejections fail the step, `end_session` answers to the plan, and the checkpoint asks when calls
 * stop settling steps.
 */
class StepVerificationTest {

    private fun planned(vararg steps: String) = RunLedgerController(
        RunLedger(
            runId = "1-a", goal = "play a song, then copy my caption", provider = "P", modelId = "m", startedAtMs = 0L,
            planSteps = steps.map { PlanStep(it) },
        ),
    )

    private fun done(index: Int, evidence: String? = "Pause button shows") = buildJsonObject {
        put("index", index)
        put("status", "done")
        evidence?.let { put("evidence", it) }
    }

    private fun text(r: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) =
        r.content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() }

    private val accept: suspend (RunLedger, Int, String) -> StepVerdict = { _, _, _ -> StepVerdict(true, "ok") }
    private val reject: suspend (RunLedger, Int, String) -> StepVerdict =
        { _, _, _ -> StepVerdict(false, "the Play button still shows") }

    // ── mark_step done ──

    @Test fun `done without evidence is refused and costs no attempt`() = runTest {
        val c = planned("play", "copy")
        val r = LedgerPlanTools(c, accept).markStep(done(0, evidence = null))
        assertEquals(true, r.isError)
        assertEquals(0, c.snapshot().planSteps[0].attempts)
        assertEquals(PlanStepStatus.PENDING, c.snapshot().planSteps[0].status)
    }

    @Test fun `a verified claim is stored done with its evidence`() = runTest {
        val c = planned("play", "copy")
        val r = LedgerPlanTools(c, accept).markStep(done(0))
        assertEquals(false, r.isError)
        assertEquals(PlanStepStatus.DONE, c.snapshot().planSteps[0].status)
        assertEquals("Pause button shows", c.snapshot().planSteps[0].evidence)
    }

    @Test fun `a rejected claim keeps the step open and says why`() = runTest {
        val c = planned("play", "copy")
        val r = LedgerPlanTools(c, reject).markStep(done(0))
        assertEquals(true, r.isError)
        assertTrue(text(r).contains("Play button still shows"))
        assertTrue(text(r).contains("Attempt 1 of 3"))
        assertEquals(PlanStepStatus.PENDING, c.snapshot().planSteps[0].status)
        assertEquals(1, c.snapshot().planSteps[0].attempts)
    }

    @Test fun `the third rejection fails the step so the run moves on`() = runTest {
        val c = planned("play", "copy")
        val tools = LedgerPlanTools(c, reject)
        repeat(3) { tools.markStep(done(0)) }
        val step = c.snapshot().planSteps[0]
        assertEquals(PlanStepStatus.FAILED, step.status)
        assertEquals("the Play button still shows", step.failReason)
    }

    @Test fun `a check that could not run is not a pass`() = runTest {
        val c = planned("play", "copy")
        LedgerPlanTools(c) { _, _, _ -> StepVerdict(false, "the check could not run", ran = false) }.markStep(done(0))
        assertEquals(PlanStepStatus.PENDING, c.snapshot().planSteps[0].status)
        assertEquals(1, c.snapshot().planSteps[0].attempts)
    }

    @Test fun `the model can give a step up with a reason`() = runTest {
        val c = planned("start navigation", "report")
        LedgerPlanTools(c, accept).markStep(
            buildJsonObject { put("index", 0); put("status", "failed"); put("note", "no Start button, only Preview") },
        )
        assertEquals(PlanStepStatus.FAILED, c.snapshot().planSteps[0].status)
        assertEquals("no Start button, only Preview", c.snapshot().planSteps[0].failReason)
    }

    @Test fun `a premature claim is sent back without using an attempt`() = runTest {
        val c = planned("play", "copy")
        val r = LedgerPlanTools(c) { _, _, _ -> StepVerdict(false, "not looked at yet", ran = false, premature = true) }
            .markStep(done(0))
        assertEquals(true, r.isError)
        assertEquals(0, c.snapshot().planSteps[0].attempts)
    }

    @Test fun `a new plan restarts the progress clock`() = runTest {
        val c = planned("a", "b")
        val tools = LedgerPlanTools(c, accept)
        tools.markStep(done(0)); tools.markStep(done(1)); c.noteTurn(2, null)
        c.setPlan(listOf("x", "y", "z"))
        tools.markStep(done(0)); c.noteTurn(1, null)
        assertEquals("settling the new plan's first step is progress", c.snapshot().toolCalls, c.snapshot().progressAtCall)
    }

    // ── end_session against the plan ──

    @Test fun `success is refused while a step is open`() = runTest {
        val c = planned("play", "copy")
        LedgerPlanTools(c, accept).markStep(done(0))
        val refusal = CompletionGate.openStepsRefusal(c.snapshot(), refusalsSoFar = 0)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("2. copy"))
        assertNull("capped like the count gate", CompletionGate.openStepsRefusal(c.snapshot(), CompletionGate.MAX_REFUSALS))
    }

    @Test fun `a failed step makes success partial, and the report says which`() = runTest {
        val c = planned("play", "copy")
        val tools = LedgerPlanTools(c, accept)
        tools.markStep(done(0))
        tools.markStep(buildJsonObject { put("index", 1); put("status", "failed"); put("note", "caption not found") })
        val ledger = c.snapshot()
        assertNull(CompletionGate.openStepsRefusal(ledger, 0))
        assertTrue(CompletionGate.planShortfall(ledger)!!.contains("caption not found"))
        val report = CompletionGate.stepReport(ledger)!!
        assertEquals("✓ play — Pause button shows\n✗ copy — caption not found", report)
    }

    @Test fun `a fully done plan has no shortfall`() = runTest {
        val c = planned("play", "copy")
        val tools = LedgerPlanTools(c, accept)
        tools.markStep(done(0)); tools.markStep(done(1, "Caption: sunset at the beach"))
        assertNull(CompletionGate.planShortfall(c.snapshot()))
    }

    @Test fun `a skipped step cannot be a quiet way to success`() = runTest {
        val c = planned("play", "copy")
        val tools = LedgerPlanTools(c, accept)
        tools.markStep(buildJsonObject { put("index", 0); put("status", "skipped") })
        tools.markStep(done(1, "Caption: sunset"))
        assertTrue(CompletionGate.planShortfall(c.snapshot())!!.contains("\"play\" was skipped"))
    }

    @Test fun `an enforced verdict speaks its reason`() {
        val line = CompletionGate.correctionFor(
            RunOutcome(claimed = "success", verdict = RunOutcome.PARTIAL, why = "only the route preview shows", enforced = true),
            trustJudge = false,
        )!!
        assertTrue(line.endsWith("Only the route preview shows."))
    }

    @Test fun `a plan shortfall is spoken even while the judge is in shadow`() {
        val line = CompletionGate.correctionFor(
            RunOutcome(claimed = "success", verdict = RunOutcome.PARTIAL, enforced = true),
            trustJudge = false,
        )
        assertNotNull(line)
    }

    // ── progress checkpoint ──

    @Test fun `no checkpoint while steps keep settling`() = runTest {
        val c = planned("a", "b", "c")
        val tools = LedgerPlanTools(c, accept)
        c.noteTurn(calls = 2, failure = null)
        tools.markStep(done(0)); c.noteTurn(calls = 1, failure = null)
        assertNull(RunLedgerRenderer.checkpoint(c.snapshot()))
    }

    @Test fun `four calls without progress ask once, then again four later`() = runTest {
        val c = planned("a", "b")
        c.noteTurn(3, null)
        assertNull(RunLedgerRenderer.checkpoint(c.snapshot()))
        c.noteTurn(2, null) // 5: crossed 4
        assertTrue(RunLedgerRenderer.checkpoint(c.snapshot())!!.contains("last 5 calls"))
        c.noteTurn(1, null) // 6: same stretch
        assertNull(RunLedgerRenderer.checkpoint(c.snapshot()))
        c.noteTurn(2, null) // 8: crossed 8
        assertNotNull(RunLedgerRenderer.checkpoint(c.snapshot()))
    }

    @Test fun `a failed call asks right away, once`() = runTest {
        val c = planned("a", "b")
        c.noteTurn(1, "tap: som_id 89 is STALE")
        assertTrue(RunLedgerRenderer.checkpoint(c.snapshot())!!.contains("STALE"))
        c.noteTurn(1, null)
        assertNull(RunLedgerRenderer.checkpoint(c.snapshot()))
    }

    // ── the active step stays in view ──

    @Test fun `the now line points at the first unsettled step, with attempts`() = runTest {
        val c = planned("play", "copy", "paste")
        val tools = LedgerPlanTools(c, accept)
        tools.markStep(done(0))
        assertEquals("▶ Now: step 2 — copy", RunLedgerRenderer.nowLine(c.snapshot()))
        LedgerPlanTools(c, reject).markStep(done(1))
        assertEquals("▶ Now: step 2 — copy (attempt 2 of 3)", RunLedgerRenderer.nowLine(c.snapshot()))
        val rendered = RunLedgerRenderer.render(c.snapshot())
        assertTrue(rendered.contains("▶ Now: step 2"))
        assertFalse(rendered.contains("CHECKPOINT"))
    }

    @Test fun `a failed step renders its reason`() = runTest {
        val c = planned("navigate", "report")
        LedgerPlanTools(c, accept).markStep(
            buildJsonObject { put("index", 0); put("status", "failed"); put("note", "only Preview") },
        )
        assertTrue(RunLedgerRenderer.render(c.snapshot()).contains("[!] navigate — failed: only Preview"))
    }
}
