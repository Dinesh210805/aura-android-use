package com.aura.aura_ui.agent.mcpbridge.hooks

import com.aura.aura_ui.agent.llm.AgentTraceTap
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolHookChainTest {
    private val noCtx = HookContext(confirm = { true })
    private fun ok(text: String = "ok") = CallToolResult(content = listOf(TextContent(text)), isError = false)
    private fun preOf(d: PreToolDecision) = object : PreToolHook {
        override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext) = d
    }

    @Test fun `proceed dispatches unchanged args`() = runTest {
        val chain = ToolHookChain(pre = emptyList(), post = emptyList())
        var seen: JsonObject? = null
        val args = buildJsonObject { put("x", 1) }
        val r = chain.runGatedToolCall("tap", args, noCtx) { seen = it; ok() }
        assertEquals(args, seen); assertEquals(false, r.isError)
    }

    @Test fun `deny returns error result and never dispatches`() = runTest {
        val chain = ToolHookChain(pre = listOf(preOf(PreToolDecision.Deny("blind tap"))), post = emptyList())
        var dispatched = false
        val r = chain.runGatedToolCall("tap", buildJsonObject {}, noCtx) { dispatched = true; ok() }
        assertTrue(r.isError == true); assertEquals(false, dispatched)
        assertTrue((r.content.first() as TextContent).text.contains("blind tap"))
    }

    @Test fun `deny wins over confirm and rewrite regardless of order`() = runTest {
        val chain = ToolHookChain(
            pre = listOf(
                preOf(PreToolDecision.Rewrite(buildJsonObject { put("x", 9) })),
                preOf(PreToolDecision.Confirm("c")),
                preOf(PreToolDecision.Deny("denied")),
            ),
            post = emptyList(),
        )
        val r = chain.runGatedToolCall("tap", buildJsonObject {}, noCtx) { ok() }
        assertTrue(r.isError == true)
    }

    @Test fun `confirm declined returns error, accepted dispatches`() = runTest {
        val chain = ToolHookChain(pre = listOf(preOf(PreToolDecision.Confirm("send?"))), post = emptyList())
        val declined = chain.runGatedToolCall("tap", buildJsonObject {}, HookContext { false }) { ok() }
        assertTrue(declined.isError == true)
        var dispatched = false
        chain.runGatedToolCall("tap", buildJsonObject {}, HookContext { true }) { dispatched = true; ok() }
        assertTrue(dispatched)
    }

    @Test fun `rewrite changes dispatched args`() = runTest {
        val chain = ToolHookChain(
            pre = listOf(preOf(PreToolDecision.Rewrite(buildJsonObject { put("x", 42) }))),
            post = emptyList(),
        )
        var seen: JsonObject? = null
        chain.runGatedToolCall("tap", buildJsonObject { put("x", 1) }, noCtx) { seen = it; ok() }
        assertEquals(42, (seen!!["x"] as JsonPrimitive).content.toInt())
    }

    @Test fun `a throwing pre-hook is contained and call proceeds`() = runTest {
        val bad = object : PreToolHook {
            override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext) =
                throw RuntimeException("boom")
        }
        val chain = ToolHookChain(pre = listOf(bad), post = emptyList())
        var dispatched = false
        val r = chain.runGatedToolCall("tap", buildJsonObject {}, noCtx) { dispatched = true; ok() }
        assertTrue(dispatched); assertEquals(false, r.isError)
    }

    @Test fun `post hooks run after dispatch with the result`() = runTest {
        var observed: CallToolResult? = null
        val post = object : PostToolHook {
            override suspend fun onPostTool(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
                observed = result
            }
        }
        val chain = ToolHookChain(pre = emptyList(), post = listOf(post))
        val r = chain.runGatedToolCall("perceive_screen", buildJsonObject {}, noCtx) { ok("seen") }
        assertEquals(r, observed)
    }

    // ── GP3/GP4: decision↔hook pairing at evaluation time ────────────────────

    /** Named so `hook::class.simpleName` is meaningful in the trace assertion. */
    private class CountingDenyHook : PreToolHook {
        var evaluations = 0
        override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext): PreToolDecision {
            evaluations++
            return PreToolDecision.Deny("blocked by test")
        }
    }

    private class CountingConfirmHook : PreToolHook {
        var evaluations = 0
        override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext): PreToolDecision {
            evaluations++
            return PreToolDecision.Confirm("really?")
        }
    }

    private class CountingRewriteHook : PreToolHook {
        var evaluations = 0
        override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext): PreToolDecision {
            evaluations++
            return PreToolDecision.Rewrite(buildJsonObject { put("x", 7) })
        }
    }

    /** Collects trace events for the duration of [block], restoring the tap afterwards. */
    private inline fun withTraceEvents(block: (MutableList<AgentTraceTap.Event>) -> Unit) {
        val events = mutableListOf<AgentTraceTap.Event>()
        AgentTraceTap.sink = { events.add(it) }
        try {
            block(events)
        } finally {
            AgentTraceTap.sink = null
        }
    }

    @Test fun `a deny is reported to the trace with the denying hook's name`() = runTest {
        withTraceEvents { events ->
            val hook = CountingDenyHook()
            val chain = ToolHookChain(pre = listOf(hook), post = emptyList())
            chain.runGatedToolCall("tap", buildJsonObject {}, noCtx) { ok() }
            val deny = events.filterIsInstance<AgentTraceTap.Event.Step>().firstOrNull { it.verdict == "deny" }
            assertEquals("CountingDenyHook", deny?.name)
            assertEquals("pre-hook", deny?.stage)
            assertEquals("blocked by test", deny?.detail)
        }
    }

    @Test fun `every hook is traced - a proceed and a post-hook included`() = runTest {
        withTraceEvents { events ->
            val chain = ToolHookChain(
                pre = listOf(object : PreToolHook {
                    override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext) = PreToolDecision.Proceed
                }),
                post = listOf(object : PostToolHook {
                    override suspend fun onPostTool(toolName: String, args: JsonObject, result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult, ctx: HookContext) = Unit
                }),
            )
            chain.runGatedToolCall("tap", buildJsonObject {}, noCtx) { ok() }
            val steps = events.filterIsInstance<AgentTraceTap.Event.Step>()
            assertEquals(listOf("pre-hook" to "proceed", "post-hook" to "ran"), steps.map { it.stage to it.verdict })
            assertTrue(steps.all { it.toolName == "tap" })
        }
    }

    @Test fun `pre-hooks are evaluated exactly once on the deny path`() = runTest {
        val hook = CountingDenyHook()
        val chain = ToolHookChain(pre = listOf(hook), post = emptyList())
        chain.runGatedToolCall("tap", buildJsonObject {}, noCtx) { ok() }
        assertEquals(1, hook.evaluations)
    }

    @Test fun `pre-hooks are evaluated exactly once on the confirm path`() = runTest {
        withTraceEvents { events ->
            val hook = CountingConfirmHook()
            val chain = ToolHookChain(pre = listOf(hook), post = emptyList())
            chain.runGatedToolCall("tap", buildJsonObject {}, noCtx) { ok() }
            assertEquals(1, hook.evaluations)
            val confirm = events.filterIsInstance<AgentTraceTap.Event.Step>().firstOrNull { it.verdict == "confirm" }
            assertEquals("CountingConfirmHook", confirm?.name)
        }
    }

    @Test fun `pre-hooks are evaluated exactly once on the rewrite path`() = runTest {
        val hook = CountingRewriteHook()
        val chain = ToolHookChain(pre = listOf(hook), post = emptyList())
        var seen: JsonObject? = null
        chain.runGatedToolCall("tap", buildJsonObject { put("x", 1) }, noCtx) { seen = it; ok() }
        assertEquals(1, hook.evaluations)
        assertEquals(7, (seen!!["x"] as JsonPrimitive).content.toInt())
    }

    // ── GP10: dispatch failures ───────────────────────────────────────────────

    @Test fun `a throwing dispatch returns an error result and the failure lane observes it`() = runTest {
        var observed: CallToolResult? = null
        val fail = object : PostToolFailureHook {
            override suspend fun onPostToolFailure(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
                observed = result
            }
        }
        val chain = ToolHookChain(pre = emptyList(), post = emptyList(), failure = listOf(fail))
        val r = chain.runGatedToolCall("use_mcp_tool", buildJsonObject {}, noCtx) {
            throw RuntimeException("transport died")
        }
        assertTrue("dispatch failure must surface as an error result", r.isError == true)
        assertTrue((r.content.first() as TextContent).text.contains("transport died"))
        assertEquals("the failure lane must observe the failure (E6)", r, observed)
    }

    @Test fun `cancellation from dispatch propagates and skips post-hooks`() = runTest {
        var postRan = false
        val post = object : PostToolHook {
            override suspend fun onPostTool(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
                postRan = true
            }
        }
        val chain = ToolHookChain(pre = emptyList(), post = listOf(post))
        var cancelled = false
        try {
            chain.runGatedToolCall("tap", buildJsonObject {}, noCtx) {
                throw kotlinx.coroutines.CancellationException("run cancelled")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation must propagate, never be converted to a result", cancelled)
        assertEquals(false, postRan)
    }
}
