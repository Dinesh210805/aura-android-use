package com.aura.aura_ui.agent.mcpbridge

import com.aura.aura_ui.agent.mcpbridge.hooks.HookContext
import com.aura.aura_ui.agent.mcpbridge.hooks.PreToolDecision
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the [PreToolHook]/[PostToolHook] adapters delegate to the unchanged
 * [ActionGuard.blockReasonFor]/[ActionGuard.recordAfter] logic. The exhaustive
 * behavioural coverage lives in [ActionGuardTest]; this only pins the adapter wiring.
 */
class ActionGuardAsHookTest {
    private val ctx = HookContext { true }

    @Test fun `onPreTool denies a blind gesture exactly as blockReasonFor does`() = runTest {
        val guard = ActionGuard()
        val d = guard.onPreTool("tap", buildJsonObject { put("x", 1); put("y", 2) }, ctx)
        assertTrue(d is PreToolDecision.Deny)
    }

    @Test fun `onPostTool grounds the screen after a successful perceive`() = runTest {
        val guard = ActionGuard()
        guard.onPostTool(
            "perceive_screen",
            buildJsonObject {},
            CallToolResult(content = listOf(TextContent("home")), isError = false),
            ctx,
        )
        val d = guard.onPreTool("tap", buildJsonObject { put("x", 1); put("y", 2) }, ctx)
        assertTrue("tap after perceive must proceed", d is PreToolDecision.Proceed)
    }
}
