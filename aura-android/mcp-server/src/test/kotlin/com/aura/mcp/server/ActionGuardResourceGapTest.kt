package com.aura.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 1f — a tool that reported a gap only the **user** can close must not be called again.
 *
 * A missing Tavily key or a disabled AURA keyboard is not transient: no sequence of tool calls
 * acquires one. Left untracked, the model's default reaction to an error — retry — repeats a call
 * that cannot succeed. That is the same shape as the control-lock finding, where a refusal that
 * read as temporary cost five identical calls and four reworded `end_session`s on a phone that was
 * never going to answer.
 */
class ActionGuardResourceGapTest {

    private suspend fun ActionGuard.sawUserGap(tool: String, hint: String) =
        noteUserFixableGap(tool, hint)

    // ── the block ────────────────────────────────────────────────────────────

    @Test
    fun `a tool that needs something only the user can supply is refused on the next call`() = runBlocking<Unit> {
        val guard = ActionGuard()
        assertNull("first call must be allowed — the gap is not known yet", guard.refusalFor("web_search", "{}"))
        guard.sawUserGap("web_search", "Tell them to paste a Tavily key in AURA Settings.")
        assertNotNull("second call must be refused", guard.refusalFor("web_search", "{}"))
    }

    /**
     * The refusal carries the tool's own next action. A block that only says "no" leaves the model
     * with nothing to tell the user, which is the state this whole change exists to end.
     */
    @Test
    fun `the refusal repeats the hint so the model can say it out loud`() = runBlocking<Unit> {
        val guard = ActionGuard()
        guard.sawUserGap("web_search", "Tell them to paste a Tavily key in AURA Settings.")
        val reason = guard.refusalFor("web_search", "{}")?.message
        assertTrue("said: $reason", reason!!.contains("paste a Tavily key in AURA Settings"))
        assertTrue("the block must name the tool", reason.contains("web_search"))
    }

    // ── the block must stay narrow ───────────────────────────────────────────

    /** One tool's missing resource says nothing about any other tool. */
    @Test
    fun `other tools are unaffected`() = runBlocking<Unit> {
        val guard = ActionGuard()
        guard.sawUserGap("web_search", "paste a key")
        guard.recordAfter("perceive_screen", "{}", success = true, screenText = "home")
        assertNull("an unrelated tool must still run", guard.refusalFor("tap", """{"x":1,"y":2}"""))
        assertNull("finishing must still be possible", guard.refusalFor("end_session", "{}"))
    }

    /**
     * Agent-fixable gaps are deliberately never recorded here. `request_screen_capture_permission`
     * exists precisely so `get_screenshot` can be retried — blocking that retry would break the
     * repair path the gap's own hint recommends. [ActionGuard.recordAfter] records only `user`
     * gaps; this pins that a guard which was never told stays out of the way.
     */
    @Test
    fun `a gap that was never reported does not block anything`() = runBlocking<Unit> {
        val guard = ActionGuard()
        assertNull(guard.refusalFor("get_screenshot", "{}"))
        assertNull(guard.refusalFor("get_screenshot", "{}"))
    }

    // ── the wiring, not just the rule ────────────────────────────────────────

    /**
     * The tests above call [ActionGuard.noteUserFixableGap] directly, so they cannot see whether
     * the real entry point reads the gap. A `resource_required` refusal is always an error
     * result, and error results take a different branch of [ActionGuard.recordAfter] than
     * successes — so this drives that entry point with the payload a tool actually returns.
     */
    @Test
    fun `a refusal arriving on the failure lane arms the block`() = runBlocking<Unit> {
        val guard = ActionGuard()
        guard.recordAfter(
            toolName = "web_search",
            argsJson = "{}",
            result = CallToolResult(
                content = listOf(
                    TextContent(
                        """{"success":false,"error":"resource_required","tool":"web_search",""" +
                            """"resource":"tavily_api_key","fixable_by":"user",""" +
                            """"hint":"Tell them to paste a key in AURA Settings."}""",
                    ),
                ),
                isError = true,
            ),
        )
        val reason = guard.refusalFor("web_search", "{}")?.message
        assertNotNull("the failure lane must arm rule 1f", reason)
        assertTrue("said: $reason", reason!!.contains("paste a key in AURA Settings"))
    }

    /** An agent-fixable refusal on the same lane must NOT arm the block — the retry is the fix. */
    @Test
    fun `an agent-fixable refusal on the failure lane does not block the retry`() = runBlocking<Unit> {
        val guard = ActionGuard()
        guard.recordAfter(
            toolName = "get_screenshot",
            argsJson = "{}",
            result = CallToolResult(
                content = listOf(
                    TextContent(
                        """{"success":false,"error":"resource_required","tool":"get_screenshot",""" +
                            """"resource":"screen_capture","fixable_by":"agent",""" +
                            """"hint":"Call request_screen_capture_permission, then retry."}""",
                    ),
                ),
                isError = true,
            ),
        )
        assertNull("blocking this breaks the repair path its own hint recommends", guard.refusalFor("get_screenshot", "{}"))
    }
}
