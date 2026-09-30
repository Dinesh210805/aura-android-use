package com.aura.aura_ui.agent.mcpbridge.hooks

import com.aura.aura_ui.agent.mcpbridge.ToolMeta
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmDestructiveHookTest {
    private val ctx = HookContext { true }

    @Test fun `destructive tool with setting on asks for confirmation`() = runTest {
        val hook = ConfirmDestructiveHook(enabled = { true }, metaFor = { ToolMeta(destructive = true) })
        assertTrue(hook.onPreTool("tap", buildJsonObject {}, ctx) is PreToolDecision.Confirm)
    }

    @Test fun `destructive tool with setting off proceeds`() = runTest {
        val hook = ConfirmDestructiveHook(enabled = { false }, metaFor = { ToolMeta(destructive = true) })
        assertTrue(hook.onPreTool("tap", buildJsonObject {}, ctx) is PreToolDecision.Proceed)
    }

    @Test fun `non-destructive tool proceeds`() = runTest {
        val hook = ConfirmDestructiveHook(enabled = { true }, metaFor = { ToolMeta(destructive = false) })
        assertTrue(hook.onPreTool("perceive_screen", buildJsonObject {}, ctx) is PreToolDecision.Proceed)
    }

    @Test fun `the question names the reply being sent, never a tool name`() = runTest {
        val hook = ConfirmDestructiveHook(enabled = { true }, metaFor = { ToolMeta(destructive = true) })
        val decision = hook.onPreTool(
            "notification_action",
            buildJsonObject { put("key", "k"); put("action", "Reply"); put("reply_text", "on my way") },
            ctx,
        ) as PreToolDecision.Confirm
        assertEquals("Send this reply now: \"on my way\"?", decision.reason)
        assertFalse(decision.reason.contains("notification_action"))
    }

    @Test fun `without reply text the question is generic and still plain`() = runTest {
        val hook = ConfirmDestructiveHook(enabled = { true }, metaFor = { ToolMeta(destructive = true) })
        val decision = hook.onPreTool("tap", buildJsonObject {}, ctx) as PreToolDecision.Confirm
        assertFalse(decision.reason.contains("tap"))
    }
}
