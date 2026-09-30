package com.aura.mcp.server

import com.aura.mcp.cache.ScreenGeneration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The success gate, driven the way the loop drives it: look, act, claim.
 *
 * [ScreenGeneration] is process-global and monotonic, so these tests never assert an absolute
 * generation number — only the relationship between a look and the mutations after it, which is
 * the whole of what the gate decides. Every test establishes its own starting point with
 * [CompletionEvidence.reset] plus a look, so ordering between tests cannot matter.
 */
class CompletionEvidenceTest {

    private fun look() = CompletionEvidence.noteLook()
    private fun act() = ScreenGeneration.bump()

    // ── freshness ────────────────────────────────────────────────────────────

    @Test
    fun `a look with nothing after it is fresh evidence`() {
        look()
        assertTrue(CompletionEvidence.isFresh())
    }

    @Test
    fun `any screen-changing call after the look makes it stale`() {
        look()
        act()
        assertFalse(CompletionEvidence.isFresh())
    }

    @Test
    fun `looking again after acting restores freshness`() {
        look()
        act()
        look()
        assertTrue(CompletionEvidence.isFresh())
    }

    @Test
    fun `a run that never looked has no evidence`() {
        CompletionEvidence.reset()
        assertFalse(CompletionEvidence.isFresh(), "never having looked must not read as looking at generation 0")
    }

    // ── the gate ─────────────────────────────────────────────────────────────

    /**
     * Eval 2026-08-26 task 3, exactly: read the screen, press enter, claim the message sent.
     * The claim is refused, and the refusal names the next action rather than just complaining.
     */
    @Test
    fun `an unverified send is refused and told what to do`() {
        look()
        act()
        val refusal = CompletionEvidence.refusalFor("success", "send_message")
        assertNotNull(refusal)
        assertTrue(refusal.contains("read_screen"), "must name the tool to call: $refusal")
        assertTrue(refusal.contains("failure"), "must say failure is still allowed: $refusal")
    }

    @Test
    fun `the same send is allowed once the agent has looked`() {
        look()
        act()
        look()
        assertNull(CompletionEvidence.refusalFor("success", "send_message"))
    }

    /** An agent that cannot report a failure is worse than one that reports a false success. */
    @Test
    fun `an honest failure is never refused`() {
        look()
        act()
        assertNull(CompletionEvidence.refusalFor("failure", "send_message"))
        assertNull(CompletionEvidence.refusalFor("failure", "purchase"))
    }

    /**
     * The gate is narrow on purpose: an extra perception turn on every mutating task is the
     * same budget this codebase is spending weeks reclaiming, and post_action_observation is
     * genuinely enough to know an app opened.
     */
    @Test
    fun `cheap goals are not gated`() {
        look()
        act()
        listOf("open_app", "search", "play_media", "navigate").forEach {
            assertNull(CompletionEvidence.refusalFor("success", it), "$it must not be gated")
        }
    }

    @Test
    fun `every gated goal type is actually gated`() {
        CompletionEvidence.GATED_GOAL_TYPES.forEach { goal ->
            CompletionEvidence.reset()
            look()
            act()
            assertNotNull(CompletionEvidence.refusalFor("success", goal), "$goal must be gated")
        }
    }

    /** Adding a goal type to the schema must not accidentally start blocking runs. */
    @Test
    fun `an unknown or absent goal type is not gated`() {
        look()
        act()
        assertNull(CompletionEvidence.refusalFor("success", null))
        assertNull(CompletionEvidence.refusalFor("success", "something_new"))
    }

    @Test
    fun `the outcome and goal type are matched case-insensitively`() {
        look()
        act()
        assertNotNull(CompletionEvidence.refusalFor("SUCCESS", "Send_Message"))
    }

    // ── session boundary ─────────────────────────────────────────────────────

    /**
     * Two tasks on one unchanged screen: without the reset the second inherits the first's
     * look and passes a gate it never earned.
     */
    @Test
    fun `a new session cannot inherit the previous one's evidence`() {
        look()
        CompletionEvidence.reset()
        assertNotNull(CompletionEvidence.refusalFor("success", "send_message"))
    }

    // ── the look set ─────────────────────────────────────────────────────────

    /**
     * `wait_for` reports that something changed, which is precisely the structural evidence
     * task 3 mistook for confirmation. Admitting it would let "press_enter then wait_for"
     * satisfy a gate built to catch that exact move.
     */
    @Test
    fun `settling and device info do not count as looking`() {
        assertFalse("wait_for" in CompletionEvidence.LOOK_TOOLS)
        assertFalse("get_device_status" in CompletionEvidence.LOOK_TOOLS)
    }

    @Test
    fun `reading the screen counts as looking`() {
        assertTrue("read_screen" in CompletionEvidence.LOOK_TOOLS)
        assertTrue("perceive_screen" in CompletionEvidence.LOOK_TOOLS)
    }
}
