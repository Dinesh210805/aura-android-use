package com.aura.aura_ui.agent.proof

import com.aura.aura_ui.agent.ledger.Finding
import com.aura.aura_ui.agent.ledger.RunLedger
import com.aura.aura_ui.agent.ledger.RunLedgerController
import com.aura.aura_ui.agent.ledger.RunOutcome
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
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
 * The proof pipeline's load-bearing decisions: a quote is only proof if the run read it, a success
 * claim is only allowed once the proof matches the declared target, and a judge verdict is only
 * trusted when it parses.
 */
class ProofPipelineTest {

    private val screen = """
        r/OnePlus  •  Posted by u/someone  4h
        Nord 4 battery drain after the latest update
        342 upvotes   58 comments
    """.trimIndent()

    private fun ledger(target: Int? = null, findings: List<Finding> = emptyList()) = RunLedger(
        runId = "1-a",
        goal = "check 10 reddit posts about the Nord 4 and summarise",
        provider = "GEMINI",
        modelId = "m",
        startedAtMs = 0L,
        targetCount = target,
        findings = findings,
    )

    private fun controller(ledger: RunLedger = ledger()) = RunLedgerController(ledger)

    private fun text(result: CallToolResult) =
        result.content.filterIsInstance<TextContent>().mapNotNull { it.text }.joinToString("\n")

    // ── ObservedText ──

    @Test fun `a quote from a read screen is found whatever its whitespace`() {
        val observed = ObservedText()
        observed.record(screen)
        assertTrue(observed.contains("Nord 4 battery drain after the latest update"))
        assertTrue(observed.contains("nord 4   battery\n drain after the latest"))
    }

    @Test fun `text the run never read is not found`() {
        val observed = ObservedText()
        observed.record(screen)
        assertFalse(observed.contains("Nord 4 camera is excellent, everyone agrees"))
        assertFalse(ObservedText().contains("anything at all"))
    }

    @Test fun `a quote too short to mean anything never counts`() {
        val observed = ObservedText()
        observed.record(screen)
        assertFalse(observed.contains("Nord"))
    }

    @Test fun `the corpus drops its oldest text once it overflows`() {
        val observed = ObservedText(maxChars = 60)
        observed.record("first screen says alpha bravo charlie delta echo foxtrot golf")
        observed.record("second screen says hotel india juliett kilo lima mike")
        assertFalse(observed.contains("alpha bravo charlie"))
        assertTrue(observed.contains("hotel india juliett"))
    }

    // ── record_finding ──

    @Test fun `record_finding stores a quoted finding and counts progress`() = runTest {
        val observed = ObservedText().apply { record(screen) }
        val c = controller(ledger(target = 10))
        val result = ProofToolLogic(c, observed).recordFinding(
            buildJsonObject {
                put("item", "Post: battery drain after update, 58 comments")
                put("quote", "Nord 4 battery drain after the latest update")
            },
        )
        assertEquals(false, result.isError)
        assertEquals(1, c.snapshot().findings.size)
        assertTrue(text(result).contains("1 of 10"))
        assertTrue(text(result).contains("keep going"))
    }

    @Test fun `record_finding refuses a quote the run never read`() = runTest {
        val observed = ObservedText().apply { record(screen) }
        val c = controller(ledger(target = 10))
        val result = ProofToolLogic(c, observed).recordFinding(
            buildJsonObject {
                put("item", "Post: everyone loves the camera")
                put("quote", "Nord 4 camera is the best in its class, say reviewers")
            },
        )
        assertEquals(true, result.isError)
        assertTrue(c.snapshot().findings.isEmpty())
        assertTrue(text(result).contains("not in anything you have read"))
    }

    @Test fun `record_finding needs both an item and a real quote`() = runTest {
        val logic = ProofToolLogic(controller(), ObservedText().apply { record(screen) })
        assertEquals(true, logic.recordFinding(buildJsonObject { put("quote", "Nord 4 battery drain") }).isError)
        assertEquals(
            true,
            logic.recordFinding(buildJsonObject { put("item", "x"); put("quote", "342") }).isError,
        )
    }

    // ── the count gate ──

    @Test fun `success is refused while the proof is short of the target`() {
        val refusal = CompletionGate.refusalFor("success", ledger(target = 10, findings = listOf(finding(), finding())), 0)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("2 of 10"))
        assertTrue(refusal.contains("partial"))
    }

    @Test fun `success passes once every item is proven`() {
        val proven = List(3) { finding() }
        assertNull(CompletionGate.refusalFor("success", ledger(target = 3, findings = proven), 0))
    }

    @Test fun `a goal with no declared count is never gated`() {
        assertNull(CompletionGate.refusalFor("success", ledger(), 0))
    }

    @Test fun `honest endings are never refused`() {
        val short = ledger(target = 10)
        assertNull(CompletionGate.refusalFor("failure", short, 0))
        assertNull(CompletionGate.refusalFor("partial", short, 0))
    }

    @Test fun `the gate stops refusing once the cap is reached, so a run always ends`() {
        val short = ledger(target = 10)
        assertNotNull(CompletionGate.refusalFor("success", short, CompletionGate.MAX_REFUSALS - 1))
        assertNull(CompletionGate.refusalFor("success", short, CompletionGate.MAX_REFUSALS))
    }

    // ── what the user hears ──

    @Test fun `a partial verdict against a success claim is spoken as a correction`() {
        val line = CompletionGate.correctionFor(
            RunOutcome(claimed = "success", verdict = RunOutcome.PARTIAL, why = "I only read 7.", proven = 7),
            trustJudge = true,
        )
        assertNotNull(line)
        assertTrue(line!!.contains("I only read 7."))
    }

    @Test fun `a counted shortfall speaks even while the judge is still in shadow mode`() {
        val line = CompletionGate.correctionFor(
            RunOutcome(claimed = "success", verdict = RunOutcome.SUCCESS, proven = 7, target = 10),
            trustJudge = false,
        )
        assertNotNull(line)
        assertTrue(line!!.contains("7 of 10"))
    }

    @Test fun `an unvalidated judge cannot contradict a run that the counts do not fault`() {
        assertNull(
            CompletionGate.correctionFor(
                RunOutcome(claimed = "success", verdict = RunOutcome.FAIL, why = "looked wrong to me"),
                trustJudge = false,
            ),
        )
    }

    @Test fun `a model that admitted partial itself is not corrected twice`() {
        assertNull(
            CompletionGate.correctionFor(
                RunOutcome(claimed = "partial", verdict = RunOutcome.PARTIAL, why = "read 7 of 10", proven = 7, target = 10),
            ),
        )
    }

    @Test fun `success and an unreachable judge add nothing to the reply`() {
        assertNull(CompletionGate.correctionFor(RunOutcome(claimed = "success", verdict = RunOutcome.SUCCESS), trustJudge = true))
        assertNull(CompletionGate.correctionFor(RunOutcome(claimed = "success", verdict = RunOutcome.UNVERIFIED), trustJudge = true))
    }

    // ── the judge's reply ──

    @Test fun `a verdict is read out of a fenced or chatty reply`() {
        val parsed = JudgePrompt.parse(
            """
            Here is my assessment:
            ```json
            {"verdict": "partial", "why": "I only read 7 of the 10 posts.", "missing": "3 more posts"}
            ```
            """.trimIndent(),
        )
        assertEquals(RunOutcome.PARTIAL, parsed?.verdict)
        assertTrue(parsed?.why?.contains("7 of the 10") == true)
        assertTrue(parsed?.why?.contains("Still to do: 3 more posts") == true)
    }

    @Test fun `a success verdict keeps its reason and drops any missing note`() {
        val parsed = JudgePrompt.parse("""{"verdict":"SUCCESS","why":"Now Playing shows DC.","missing":""}""")
        assertEquals(RunOutcome.SUCCESS, parsed?.verdict)
        assertEquals("Now Playing shows DC.", parsed?.why)
    }

    @Test fun `an unusable reply yields no verdict rather than a guess`() {
        assertNull(JudgePrompt.parse("I think it went mostly fine"))
        assertNull(JudgePrompt.parse("""{"verdict":"mostly","why":"eh"}"""))
        assertNull(JudgePrompt.parse("""{"verdict":"partial",,,"""))
    }

    @Test fun `the prompt carries the goal, the claim, the findings and the screen`() {
        val observed = ObservedText().apply { record(screen) }
        val filled = JudgePrompt.fill(
            template = "GOAL {{GOAL}} CLAIMED {{CLAIMED}} ANSWER {{ANSWER}} N {{FINDING_COUNT}} " +
                "F {{FINDINGS}} S {{STEPS}} SCREENS {{SCREENS}}",
            ledger = ledger(target = 10, findings = listOf(finding())),
            claimed = "success",
            answer = "I reviewed over ten posts",
            observed = observed,
        )
        assertTrue(filled.contains("check 10 reddit posts"))
        assertTrue(filled.contains("CLAIMED success"))
        assertTrue(filled.contains("I reviewed over ten posts"))
        assertTrue(filled.contains("N 1"))
        assertTrue(filled.contains("battery drain"))
        assertTrue(filled.contains("nord 4 battery drain"))
    }

    @Test fun `a run that never called end_session says so in the prompt`() {
        val filled = JudgePrompt.fill("CLAIMED {{CLAIMED}}", ledger(), null, "done!", ObservedText())
        assertTrue(filled.contains("never said"))
    }

    private fun finding() = Finding(
        item = "Post: battery drain after update",
        quote = "Nord 4 battery drain after the latest update",
    )
}
