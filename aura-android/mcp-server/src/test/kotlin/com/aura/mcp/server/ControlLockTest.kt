package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 2026-07-31 (browser automation) — *Control ownership: one lock, three holders*.
 *
 * The rule being encoded: **the human always wins**. When the user picks up their own
 * phone, every driver stops — the local agent and any remote MCP client alike.
 *
 * Note what [ControlLock.evaluate] does NOT take: a tool name. That is the design. An
 * allow-list of "tools that are still fine while paused" is a list someone eventually
 * gets wrong, and the spec is explicit that read-only tools are blocked too —
 * screenshotting someone's screen the instant they grab their phone is precisely when
 * not to. With no tool name in the signature, the hole cannot be introduced by accident.
 */
class ControlLockTest {

    @Test
    fun `a read-only tool is refused while the human holds the lock`() {
        // READ, not WRITE, on purpose: this is the case a "block dangerous things"
        // reading of the spec would wrongly allow.
        val verdict = ControlLock.evaluate(pausedByHuman = true)

        assertIs<ControlLock.Verdict.Block>(verdict)
    }

    @Test
    fun `calls flow normally when the human is not holding the lock`() {
        val verdict = ControlLock.evaluate(pausedByHuman = false)

        assertIs<ControlLock.Verdict.Allow>(verdict)
    }

    @Test
    fun `the refusal tells the caller to wait rather than to retry`() {
        val block = ControlLock.evaluate(pausedByHuman = true) as ControlLock.Verdict.Block

        // A remote client that reads "error" and retries turns a pause into a fight for
        // the user's phone. The message has to say what is happening and what to do.
        //
        // Asserted on intent, not on exact wording (2026-08-05): the original version
        // pinned the literal substring "using their phone", which fails any rephrasing
        // that means the same thing.
        assertTrue(block.message.contains("phone"), "must say why: '${block.message}'")
        assertTrue(block.message.contains("resume"), "must name the way out: '${block.message}'")

        // The stricter half, added after the device traces of 2026-08-05. The old wording
        // ("Wait and try again in a moment") was followed literally: five identical
        // get_ui_tree retries, then four reworded end_session attempts, each a full ~20k
        // token round trip against a quota two runs went on to exhaust. A refusal that
        // suggests retrying gets retried, so the message must never suggest it.
        assertTrue(
            !block.message.contains("try again", ignoreCase = true),
            "must not invite a retry: '${block.message}'",
        )
        assertTrue(
            block.message.contains("not retry", ignoreCase = true),
            "must say plainly not to retry: '${block.message}'",
        )
    }
}
