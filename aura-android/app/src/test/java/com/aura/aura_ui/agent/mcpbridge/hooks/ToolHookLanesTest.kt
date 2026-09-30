package com.aura.aura_ui.agent.mcpbridge.hooks

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E6 — lane routing + GP14 confirm composition on the chain. */
class ToolHookLanesTest {

    private fun confirmHook(reason: String) = object : PreToolHook {
        override suspend fun onPreTool(toolName: String, args: JsonObject, ctx: HookContext) =
            PreToolDecision.Confirm(reason)
    }

    private fun okDispatch(): suspend (JsonObject) -> CallToolResult =
        { CallToolResult(content = listOf(TextContent("ok")), isError = false) }

    @Test
    fun `multiple confirm reasons compose into one ask (GP14)`() = runTest {
        val asked = mutableListOf<String>()
        val chain = ToolHookChain(pre = listOf(confirmHook("reason A"), confirmHook("reason B")), post = emptyList())
        val ctx = HookContext(confirm = { r -> asked.add(r); true })
        val result = chain.runGatedToolCall("tap", buildJsonObject {}, ctx, okDispatch())
        assertEquals(1, asked.size)
        assertTrue(asked.single().contains("reason A"))
        assertTrue(asked.single().contains("reason B"))
        assertTrue(result.isError != true)
    }

    @Test
    fun `declining a composed confirm denies with both reasons`() = runTest {
        val chain = ToolHookChain(pre = listOf(confirmHook("A"), confirmHook("B")), post = emptyList())
        val ctx = HookContext(confirm = { false })
        val result = chain.runGatedToolCall("tap", buildJsonObject {}, ctx, okDispatch())
        assertTrue(result.isError == true)
        val text = (result.content.single() as TextContent).text.orEmpty()
        assertTrue(text.contains("A") && text.contains("B"))
    }

    @Test
    fun `isError results route to the failure lane only`() = runTest {
        val postSeen = mutableListOf<String>()
        val failSeen = mutableListOf<String>()
        val post = object : PostToolHook {
            override suspend fun onPostTool(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
                postSeen.add(toolName)
            }
        }
        val fail = object : PostToolFailureHook {
            override suspend fun onPostToolFailure(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
                failSeen.add(toolName)
            }
        }
        val chain = ToolHookChain(pre = emptyList(), post = listOf(post), failure = listOf(fail))
        val ctx = HookContext(confirm = { true })
        chain.runGatedToolCall("tap", buildJsonObject {}, ctx) {
            CallToolResult(content = listOf(TextContent("boom")), isError = true)
        }
        chain.runGatedToolCall("swipe", buildJsonObject {}, ctx) {
            CallToolResult(content = listOf(TextContent("ok")), isError = false)
        }
        assertEquals(listOf("swipe"), postSeen)
        assertEquals(listOf("tap"), failSeen)
    }

    @Test
    fun `a thrown dispatch routes to the failure lane`() = runTest {
        val failSeen = mutableListOf<String>()
        val fail = object : PostToolFailureHook {
            override suspend fun onPostToolFailure(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
                failSeen.add(toolName)
            }
        }
        val chain = ToolHookChain(pre = emptyList(), post = emptyList(), failure = listOf(fail))
        chain.runGatedToolCall("tap", buildJsonObject {}, HookContext(confirm = { true })) { error("bridge died") }
        assertEquals(listOf("tap"), failSeen)
    }

    @Test
    fun `a throwing failure hook is contained`() = runTest {
        val fail = object : PostToolFailureHook {
            override suspend fun onPostToolFailure(toolName: String, args: JsonObject, result: CallToolResult, ctx: HookContext) {
                error("bad hook")
            }
        }
        val chain = ToolHookChain(pre = emptyList(), post = emptyList(), failure = listOf(fail))
        val result = chain.runGatedToolCall("tap", buildJsonObject {}, HookContext(confirm = { true })) {
            CallToolResult(content = listOf(TextContent("boom")), isError = true)
        }
        assertTrue(result.isError == true) // the original result survives the bad hook
    }
}
