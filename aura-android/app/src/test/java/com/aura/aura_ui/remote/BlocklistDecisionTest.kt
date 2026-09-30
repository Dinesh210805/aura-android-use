package com.aura.aura_ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Blocklist.decide] — the rule that decides whether a lookup that did not happen means "not
 * blocked". It does not, and that is the whole reason this is a function with tests rather than
 * three lines inside a Firestore callback.
 */
class BlocklistDecisionTest {

    private fun verdict(blocked: Boolean, message: String? = null) = Blocklist.Verdict(blocked, message)

    // ── a lookup that completed is authoritative, both ways ──────────────────

    @Test fun `a completed lookup that finds an entry blocks`() {
        val out = Blocklist.decide(verdict(true, "no"), cachedBlocked = false, cachedMessage = null)
        assertTrue(out.blocked)
        assertEquals("no", out.message)
    }

    /**
     * The only path back. Unblocking has to be possible, and only a lookup that actually reached
     * Firestore may do it — anything weaker and removing an entry would never take effect.
     */
    @Test fun `a completed lookup that finds nothing clears a cached block`() {
        val out = Blocklist.decide(verdict(false), cachedBlocked = true, cachedMessage = "no")
        assertFalse(out.blocked)
        assertNull(out.message)
    }

    // ── a lookup that did not complete falls back to memory ──────────────────

    /**
     * The bypass this closes: fail-open plus no memory means a blocked phone is one airplane-mode
     * toggle away from working again.
     */
    @Test fun `going offline does not unblock a device that was already blocked`() {
        val out = Blocklist.decide(remote = null, cachedBlocked = true, cachedMessage = "no")
        assertTrue(out.blocked)
        assertEquals("the message must survive too, or a block appears as a generic failure", "no", out.message)
    }

    /**
     * The other direction, and why this is not simply "deny when unsure": an install that has
     * never been told anything gets the benefit of the doubt, exactly like RemoteGateManager's
     * fetch. Only an install that HAS been told does not get to forget it.
     */
    @Test fun `a failed lookup on a device never blocked leaves it allowed`() {
        assertFalse(Blocklist.decide(remote = null, cachedBlocked = false, cachedMessage = null).blocked)
    }

    /**
     * A stale message with no block behind it would surface as a wall with no cause, so the two
     * fields are kept consistent rather than carried independently.
     */
    @Test fun `a cached message without a cached block is discarded`() {
        val out = Blocklist.decide(remote = null, cachedBlocked = false, cachedMessage = "left over")
        assertFalse(out.blocked)
        assertNull(out.message)
    }

    // ── how often Firestore is asked ─────────────────────────────────────────

    private val day = Blocklist.CHECK_INTERVAL_MS
    private val now = 1_700_000_000_000L

    @Test fun `never checked means check now`() =
        assertTrue(Blocklist.isCheckDue(nowMs = now, lastCheckMs = 0L, keysChanged = false))

    /** The quota rule: an app opened fifty times a day costs one lookup, not fifty. */
    @Test fun `checked an hour ago on the same keys means no lookup`() =
        assertFalse(Blocklist.isCheckDue(nowMs = now, lastCheckMs = now - 3_600_000L, keysChanged = false))

    @Test fun `a day later the check runs again`() =
        assertTrue(Blocklist.isCheckDue(nowMs = now, lastCheckMs = now - day, keysChanged = false))

    /** Signing in adds an account key, which could be on the blocklist: don't wait a day. */
    @Test fun `new keys are checked at once`() =
        assertTrue(Blocklist.isCheckDue(nowMs = now, lastCheckMs = now - 60_000L, keysChanged = true))

    @Test fun `a clock that jumped backwards does not postpone the check`() =
        assertTrue(Blocklist.isCheckDue(nowMs = now, lastCheckMs = now + day, keysChanged = false))
}
