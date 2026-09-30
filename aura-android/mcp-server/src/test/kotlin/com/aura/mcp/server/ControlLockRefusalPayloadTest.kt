package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 2026-07-31 — the wire shape of a refusal. The spec names `paused_by_user: true`
 * specifically, because a remote client needs to distinguish "the user has the phone"
 * from every other error. Those demand opposite responses: most errors deserve a retry
 * or a different approach, and this one deserves *waiting*.
 */
class ControlLockRefusalPayloadTest {

    private fun payload(toolName: String = "tap") =
        Json.parseToJsonElement(
            (pausedByUserResult(toolName, ControlLock.evaluate(true) as ControlLock.Verdict.Block)
                .content.single() as TextContent).text.orEmpty(),
        ).jsonObject

    @Test
    fun `the refusal is flagged as an error`() {
        val result = pausedByUserResult("tap", ControlLock.evaluate(true) as ControlLock.Verdict.Block)

        assertEquals(true, result.isError)
    }

    @Test
    fun `it carries the paused_by_user flag the spec names`() {
        assertEquals(true, payload()["paused_by_user"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `the error code is distinguishable from every other block`() {
        // scope_denied, policy_blocked and foreground_blocked all mean "never going to
        // work". This one means "works fine in a minute" — a client that cannot tell
        // them apart will either give up on a recoverable pause or hammer a hard block.
        assertEquals("paused_by_user", payload()["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `it names the tool that was refused`() {
        assertEquals("browser_act", payload("browser_act")["tool"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the hint tells the client to wait instead of retrying in a loop`() {
        val hint = payload()["hint"]?.jsonPrimitive?.content.orEmpty()

        // Without this, the likeliest client behaviour on an error is an immediate retry —
        // which is an agent fighting its user for their own phone, at the exact moment
        // they reached for it.
        assertTrue(hint.contains("retry", ignoreCase = true), "hint must address retrying: '$hint'")
        assertTrue(hint.isNotBlank())
    }
}
