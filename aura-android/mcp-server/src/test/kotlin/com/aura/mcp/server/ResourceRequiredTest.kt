package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The refusal for "this tool needs something nobody gave it yet".
 *
 * The behaviour worth pinning is not the JSON — it is that `fixable_by` distinguishes a gap the
 * agent can close from one only the user can. Collapsing those is the defect this shape exists to
 * fix, and the one the control lock already paid for on device.
 */
class ResourceRequiredTest {

    private fun payloadOf(
        toolName: String = "web_search",
        resource: String = ResourceRequired.Resource.TAVILY_API_KEY,
        fixableBy: ResourceRequired.Fixer = ResourceRequired.Fixer.USER,
        message: String = "Web search needs a Tavily API key.",
        hint: String = "Tell the user to paste a key in AURA Settings.",
    ): String {
        val result = resourceRequiredResult(toolName, resource, fixableBy, message, hint)
        return (result.content.single() as TextContent).text
    }

    @Test
    fun `a refusal is always an error result`() {
        val result = resourceRequiredResult(
            "web_search", ResourceRequired.Resource.TAVILY_API_KEY,
            ResourceRequired.Fixer.USER, "missing", "tell the user",
        )
        assertEquals(true, result.isError)
    }

    @Test
    fun `it carries the family's error code plus the fields a reader branches on`() {
        val payload = payloadOf()
        assertTrue(payload.contains("\"error\":\"resource_required\""))
        assertTrue(payload.contains("\"tool\":\"web_search\""))
        assertTrue(payload.contains("\"resource\":\"tavily_api_key\""))
        assertTrue(payload.contains("\"fixable_by\":\"user\""))
        assertTrue(payload.contains("\"success\":false"))
    }

    @Test
    fun `agent-fixable and user-fixable are distinguishable on the wire`() {
        assertTrue(payloadOf(fixableBy = ResourceRequired.Fixer.AGENT).contains("\"fixable_by\":\"agent\""))
        assertTrue(payloadOf(fixableBy = ResourceRequired.Fixer.USER).contains("\"fixable_by\":\"user\""))
    }

    /**
     * `permission_required` was the flag three tools used before this shape existed, and it always
     * really meant "the agent can go and get this". Emitting it for a user-fixable gap would tell
     * an old reader to request a permission no tool can request — a retry loop.
     */
    @Test
    fun `the legacy permission flag rides only with agent-fixable gaps`() {
        assertTrue(payloadOf(fixableBy = ResourceRequired.Fixer.AGENT).contains("\"permission_required\":true"))
        assertFalse(payloadOf(fixableBy = ResourceRequired.Fixer.USER).contains("permission_required"))
    }

    /**
     * The default reaction to an error is a retry; for a user-fixable gap the only useful move is
     * a specific sentence to a specific person. A refusal without one leaves the user with a
     * failed task and no reason, which is the state this whole change exists to end.
     */
    @Test
    fun `a refusal with no next action refuses to be built`() {
        assertFailsWith<IllegalArgumentException> {
            resourceRequiredResult(
                "web_search", ResourceRequired.Resource.TAVILY_API_KEY,
                ResourceRequired.Fixer.USER, "missing", hint = "   ",
            )
        }
    }
}
