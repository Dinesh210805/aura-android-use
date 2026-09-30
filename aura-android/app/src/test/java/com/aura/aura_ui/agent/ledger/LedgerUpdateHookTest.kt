package com.aura.aura_ui.agent.ledger

import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerUpdateHookTest {

    private fun controller() = RunLedgerController(
        RunLedger(
            runId = "1-a",
            goal = "send an email",
            provider = "GROQ",
            modelId = "m",
            startedAtMs = 0L,
        ),
    )

    private val ctx = HookContext(confirm = { true })

    private fun ok() = CallToolResult(content = listOf(TextContent("ok")), isError = false)

    private fun err() = CallToolResult(content = listOf(TextContent("boom")), isError = true)

    private fun okWithObservation(screenChanged: Boolean) = CallToolResult(
        content = listOf(
            TextContent("""{"success":true,"action":"tap"}"""),
            TextContent(
                """{"post_action_observation":{"settled":true,"screen_changed":$screenChanged,""" +
                    """"foreground_app":"com.google.android.gm","element_count":12}}""",
            ),
        ),
        isError = false,
    )

    private suspend fun post(hook: LedgerUpdateHook, tool: String, args: JsonObject, result: CallToolResult = ok()) =
        hook.onPostTool(tool, args, result, ctx)

    /** E6 — errored results reach the hook on its failure lane, like the chain routes them. */
    private suspend fun fail(hook: LedgerUpdateHook, tool: String, args: JsonObject, result: CallToolResult = err()) =
        hook.onPostToolFailure(tool, args, result, ctx)

    @Test fun `a successful gesture is recorded with its label and screen_changed signal`() = runTest {
        val c = controller()
        val hook = LedgerUpdateHook(c)
        post(hook, "tap", buildJsonObject { put("som_id", 3); put("label", "Compose") }, okWithObservation(true))
        val step = c.snapshot().recentSteps.single()
        assertEquals("tap", step.tool)
        assertEquals("Compose", step.label)
        assertTrue(step.ok)
        assertEquals(true, step.screenChanged)
    }

    @Test fun `read-only grounding tools record nothing`() = runTest {
        val c = controller()
        val hook = LedgerUpdateHook(c)
        post(hook, "perceive_screen", buildJsonObject {})
        post(hook, "read_screen", buildJsonObject {})
        assertTrue(c.snapshot().recentSteps.isEmpty())
        assertEquals(0, c.snapshot().totalSteps)
    }

    @Test fun `end_session records nothing`() = runTest {
        val c = controller()
        val hook = LedgerUpdateHook(c)
        post(hook, "end_session", buildJsonObject {})
        assertTrue(c.snapshot().recentSteps.isEmpty())
    }

    @Test fun `an errored gesture is a failed step and a dead end`() = runTest {
        val c = controller()
        val hook = LedgerUpdateHook(c)
        fail(hook, "tap", buildJsonObject { put("label", "Send") })
        val ledger = c.snapshot()
        assertEquals(1, ledger.failedSteps)
        assertEquals(false, ledger.recentSteps.single().ok)
        assertTrue(ledger.deadEnds.single().contains("Send"))
        assertTrue(ledger.deadEnds.single().contains("failed"))
    }

    @Test fun `a no-effect write is recorded and remembered as a dead end`() = runTest {
        val c = controller()
        val hook = LedgerUpdateHook(c)
        post(hook, "press_enter", buildJsonObject {}, okWithObservation(false))
        val ledger = c.snapshot()
        assertEquals(false, ledger.recentSteps.single().screenChanged)
        assertTrue(ledger.deadEnds.single().contains("press_enter"))
        assertTrue(ledger.deadEnds.single().contains("no effect"))
    }

    @Test fun `typed text is redacted in the step label`() = runTest {
        val c = controller()
        val hook = LedgerUpdateHook(c)
        post(hook, "type_text", buildJsonObject { put("text", "my secret draft") })
        val step = c.snapshot().recentSteps.single()
        assertEquals("<redacted>", step.label)
        assertTrue(c.snapshot().recentSteps.none { it.label?.contains("secret") == true })
    }

    @Test fun `a gesture without an observation bundle records screenChanged as unknown`() = runTest {
        val c = controller()
        val hook = LedgerUpdateHook(c)
        post(hook, "tap", buildJsonObject { put("label", "OK") }, ok())
        assertEquals(null, c.snapshot().recentSteps.single().screenChanged)
        assertTrue("no dead end without a definite no-effect signal", c.snapshot().deadEnds.isEmpty())
    }
}
