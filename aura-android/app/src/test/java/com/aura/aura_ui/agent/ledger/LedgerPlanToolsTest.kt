package com.aura.aura_ui.agent.ledger

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerPlanToolsTest {

    private fun controller() = RunLedgerController(
        RunLedger(runId = "1-a", goal = "g", provider = "GROQ", modelId = "m", startedAtMs = 0L),
    )

    private fun text(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() }

    // ── set_plan ──

    @Test fun `set_plan replaces the checklist and confirms it`() = runTest {
        val c = controller()
        val tools = LedgerPlanTools(c)
        val result = tools.setPlan(
            buildJsonObject {
                put("steps", buildJsonArray { add("open gmail"); add("compose"); add("send") })
            },
        )
        assertEquals(false, result.isError)
        assertEquals(listOf("open gmail", "compose", "send"), c.snapshot().planSteps.map { it.text })
        assertTrue(text(result).contains("3"))
    }

    @Test fun `set_plan without steps is an error`() = runTest {
        val tools = LedgerPlanTools(controller())
        assertEquals(true, tools.setPlan(buildJsonObject {}).isError)
        assertEquals(true, tools.setPlan(buildJsonObject { put("steps", buildJsonArray {}) }).isError)
    }

    @Test fun `set_plan ignores blank steps and caps the list`() = runTest {
        val c = controller()
        val tools = LedgerPlanTools(c)
        tools.setPlan(
            buildJsonObject {
                put("steps", buildJsonArray { add("a"); add("   "); add("b") })
            },
        )
        assertEquals(listOf("a", "b"), c.snapshot().planSteps.map { it.text })

        val many = buildJsonObject {
            put("steps", buildJsonArray { repeat(LedgerPlanTools.MAX_PLAN_STEPS + 10) { add("step $it") } })
        }
        tools.setPlan(many)
        assertEquals(LedgerPlanTools.MAX_PLAN_STEPS, c.snapshot().planSteps.size)
    }

    // ── mark_step ──

    @Test fun `mark_step done updates status and reports progress`() = runTest {
        val c = controller()
        val tools = LedgerPlanTools(c)
        tools.setPlan(buildJsonObject { put("steps", buildJsonArray { add("a"); add("b") }) })
        val result = tools.markStep(buildJsonObject { put("index", 0); put("status", "done"); put("evidence", "Sent") })
        assertEquals(false, result.isError)
        assertEquals(PlanStepStatus.DONE, c.snapshot().planSteps[0].status)
        assertTrue("progress expected in confirmation", text(result).contains("1/2"))
    }

    @Test fun `mark_step accepts in_progress and skipped`() = runTest {
        val c = controller()
        val tools = LedgerPlanTools(c)
        tools.setPlan(buildJsonObject { put("steps", buildJsonArray { add("a"); add("b") }) })
        tools.markStep(buildJsonObject { put("index", 0); put("status", "in_progress") })
        tools.markStep(buildJsonObject { put("index", 1); put("status", "skipped") })
        assertEquals(PlanStepStatus.IN_PROGRESS, c.snapshot().planSteps[0].status)
        assertEquals(PlanStepStatus.SKIPPED, c.snapshot().planSteps[1].status)
    }

    @Test fun `mark_step with a note records it as a fact`() = runTest {
        val c = controller()
        val tools = LedgerPlanTools(c)
        tools.setPlan(buildJsonObject { put("steps", buildJsonArray { add("check price") }) })
        tools.markStep(
            buildJsonObject { put("index", 0); put("status", "done"); put("evidence", "134900"); put("note", "price is 134900") },
        )
        assertTrue(c.snapshot().facts.any { it.contains("134900") })
    }

    @Test fun `mark_step rejects bad input with model-readable errors`() = runTest {
        val c = controller()
        val tools = LedgerPlanTools(c)
        tools.setPlan(buildJsonObject { put("steps", buildJsonArray { add("a") }) })
        assertEquals(true, tools.markStep(buildJsonObject { put("status", "done") }).isError)
        assertEquals(true, tools.markStep(buildJsonObject { put("index", 0) }).isError)
        assertEquals(true, tools.markStep(buildJsonObject { put("index", 0); put("status", "finished") }).isError)
        val outOfRange = tools.markStep(buildJsonObject { put("index", 9); put("status", "done") })
        assertEquals(true, outOfRange.isError)
        assertTrue("error should tell the model the valid range", text(outOfRange).contains("0"))
        assertEquals(PlanStepStatus.PENDING, c.snapshot().planSteps[0].status)
    }

    @Test fun `mark_step before any plan is an error nudging set_plan`() = runTest {
        val tools = LedgerPlanTools(controller())
        val result = tools.markStep(buildJsonObject { put("index", 0); put("status", "done") })
        assertEquals(true, result.isError)
        assertTrue(text(result).contains("set_plan"))
    }
}
