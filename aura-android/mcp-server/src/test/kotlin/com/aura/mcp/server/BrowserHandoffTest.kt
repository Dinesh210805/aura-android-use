package com.aura.mcp.server

import com.aura.mcp.bridge.McpScope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The browser handoff — "I need you", and the road back.
 *
 * The tests that matter here are the ones about what stays *open* while the human has the
 * browser. A handoff that blocked everything would be permanent, because the condition
 * that ends it can only be observed by reading the page.
 */
class BrowserHandoffTest {

    @Test
    fun `with no handoff everything passes`() {
        assertEquals(BrowserHandoff.Verdict.Allow, BrowserHandoff.evaluate(false, McpScope.WRITE))
        assertEquals(BrowserHandoff.Verdict.Allow, BrowserHandoff.evaluate(false, McpScope.READ))
    }

    @Test
    fun `a handoff stops the agent touching the page`() {
        assertTrue(BrowserHandoff.evaluate(true, McpScope.WRITE) is BrowserHandoff.Verdict.Block)
    }

    @Test
    fun `a handoff leaves READ open, or it could never end`() {
        // THE load-bearing case. Auto-resume's first condition is "the login form is gone",
        // which requires reading the page. Block reads and nothing can ever observe the
        // condition that ends the handoff — every login would strand the agent until the
        // user found the pill. This is why the browser lock blocks by SCOPE while the human
        // control lock deliberately blocks by nothing at all.
        assertEquals(BrowserHandoff.Verdict.Allow, BrowserHandoff.evaluate(true, McpScope.READ))
    }

    @Test
    fun `the refusal tells the agent what it may still do`() {
        val block = BrowserHandoff.evaluate(true, McpScope.WRITE) as BrowserHandoff.Verdict.Block

        // A refusal that only says "no" invites a retry loop against a human.
        assertTrue(block.message.contains("browser_read"))
        assertTrue(block.message.contains("Resume"))
    }

    // ── auto-resume: all three conditions, never one alone ───────────────

    @Test
    fun `all three conditions together hand the browser back`() {
        assertTrue(
            BrowserHandoff.shouldAutoResume(
                loginFormGone = true,
                msSinceLastTouch = BrowserHandoff.QUIET_MS,
                windowFrontmost = true,
            ),
        )
    }

    @Test
    fun `a still-present form holds the handoff no matter how long they pause`() {
        // People stop to read an OTP, or think about which account to use. Stillness alone
        // is not "finished".
        assertFalse(
            BrowserHandoff.shouldAutoResume(
                loginFormGone = false,
                msSinceLastTouch = 60_000,
                windowFrontmost = true,
            ),
        )
    }

    @Test
    fun `an unreadable page is not the same as a finished one`() {
        // Null means the engine could not tell. Treating "I don't know" as "it's gone" is
        // how the agent starts clicking while somebody is still typing a password.
        assertFalse(
            BrowserHandoff.shouldAutoResume(
                loginFormGone = null,
                msSinceLastTouch = 60_000,
                windowFrontmost = true,
            ),
        )
    }

    @Test
    fun `mid-typing stillness is not enough`() {
        assertFalse(
            BrowserHandoff.shouldAutoResume(
                loginFormGone = true,
                msSinceLastTouch = BrowserHandoff.QUIET_MS - 1,
                windowFrontmost = true,
            ),
        )
    }

    @Test
    fun `the agent waits while the user is off reading the OTP`() {
        // They switched to Messages to fetch a code. Resuming now means they come back to a
        // page AURA has already navigated away from, holding a code for a form that is gone.
        assertFalse(
            BrowserHandoff.shouldAutoResume(
                loginFormGone = true,
                msSinceLastTouch = 60_000,
                windowFrontmost = false,
            ),
        )
    }
}
