package com.aura.aura_ui.agent.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 + F3 of `docs/superpowers/specs/2026-08-13-provider-limits-and-capabilities-design.md`.
 *
 * The old `RunTokenBudget` bounded a run by tokens alone, and was fed a `chars/4` guess while the
 * provider's exact `usage` object sat one hop away in `AgentLlmTap`. Tokens are the only one of the
 * three available meters that can lie: counting requests and wall-clock is free, exact, and needs
 * no cooperation from the provider — which is what makes it work on an endpoint we have never seen.
 *
 * ### Why counting happens at the wire
 *
 * A first version paired one pre-flight estimate with one reported actual. That pairing was wrong
 * in three ways at once: the strategy charges only in the tool-result node, so the turn-0 request
 * and every `EmptyTurnPolicy` nudge were LLM calls nobody counted; and a retried call reports usage
 * more than once, which the pairing dropped. Requests and actuals are therefore counted where every
 * call provably passes — the HTTP tap — and the estimate is kept only as the fallback for providers
 * that report no usage at all.
 */
class RunBudgetTest {

    private fun budget(
        maxRequests: Int? = null,
        maxWallClockMs: Long = Long.MAX_VALUE,
        clock: () -> Long = { 0L },
    ) = RunBudget(maxRequests, maxWallClockMs, clock)

    // ── 2026-09-22: tokens are an instrument, never a ceiling ─────────────────

    /**
     * The regression that killed 8 of 25 runs in the 2026-09-21 bench. A chat API re-sends the
     * whole conversation every turn, so summing each request's prompt re-counts the same system
     * block and tool schemas over and over: the sum grows quadratically in turns while the
     * conversation grows linearly. Whatever number that sum reaches, it must not end a run.
     */
    @Test fun `a huge token total never ends a run`() {
        val b = budget()
        repeat(200) { b.recordWireCall(totalTokens = 20_000) }
        assertEquals(4_000_000, b.spentTokens())
        assertNull(b.exceededLimit())
        assertFalse(b.exceeded())
    }

    /**
     * `cached_tokens` is an optional usage field: Gemini fills it, Groq and most OpenAI-compat
     * proxies do not. While tokens were a ceiling that one field decided how far the agent got —
     * ~10x difference on identical work. Both shapes must now reach the same verdict: none.
     */
    @Test fun `whether the provider reports caching changes no verdict`() {
        val reporting = budget()
        val silent = budget()
        repeat(30) {
            reporting.recordWireCall(totalTokens = 18_000, cachedTokens = 14_000)
            silent.recordWireCall(totalTokens = 18_000, cachedTokens = null)
        }
        assertEquals(120_000, reporting.spentTokens())
        assertEquals(540_000, silent.spentTokens())
        assertNull(reporting.exceededLimit())
        assertNull(silent.exceededLimit())
    }

    // ── tokens: the estimate is only a fallback ───────────────────────────────

    @Test fun `estimates accumulate while the provider reports nothing`() {
        val b = budget()
        b.chargeEstimate(300)
        b.chargeEstimate(250)
        assertEquals(550, b.spentTokens())
        assertFalse(b.exceeded())
    }

    @Test fun `non-positive estimates are ignored`() {
        val b = budget()
        b.chargeEstimate(-50)
        b.chargeEstimate(0)
        assertEquals(0, b.spentTokens())
        assertFalse(b.exceeded())
    }

    // ── F3: reported usage supersedes the guess entirely ──────────────────────

    @Test fun `once the provider reports usage the estimate stops counting`() {
        val b = budget()
        b.chargeEstimate(1_000) // our guess…
        b.recordWireCall(4_000) // …the provider's own number
        assertEquals(4_000, b.spentTokens())
    }

    @Test fun `reported usage accumulates across calls`() {
        val b = budget()
        b.chargeEstimate(1_000); b.recordWireCall(2_500)
        b.chargeEstimate(1_000); b.recordWireCall(3_500)
        assertEquals(6_000, b.spentTokens())
    }

    /**
     * A retried call reports usage once per attempt that got a response body. Each of those
     * attempts really did consume tokens, so each is counted — the previous pairing design threw
     * every attempt after the first away.
     */
    @Test fun `every attempt of a retried call is counted`() {
        val b = budget()
        b.chargeEstimate(1_000)
        b.recordWireCall(2_000)
        b.recordWireCall(2_000)
        assertEquals(4_000, b.spentTokens())
    }

    @Test fun `a provider that reports no usage leaves the estimate in charge`() {
        val b = budget()
        b.chargeEstimate(800)
        b.recordWireCall(null)
        assertEquals(800, b.spentTokens())
    }

    /** Mixed reporting must not double-count: once anything is real, the guesses are dropped. */
    @Test fun `estimates are not added on top of reported usage`() {
        val b = budget()
        b.chargeEstimate(9_000)
        b.recordWireCall(1_000)
        b.chargeEstimate(9_000)
        assertEquals(1_000, b.spentTokens())
    }

    // ── F1: requests — counted at the wire, so nothing escapes ────────────────

    @Test fun `counts every wire call and trips on the request ceiling`() {
        val b = budget(maxRequests = 3)
        repeat(2) { b.recordWireCall(10) }
        assertEquals(2, b.requests())
        assertFalse(b.exceeded())
        b.recordWireCall(10)
        assertTrue(b.exceeded())
        assertEquals(RunBudget.Limit.REQUESTS, b.exceededLimit())
    }

    /**
     * The regression the wire-counting fix exists for: calls the strategy never charged (turn 0,
     * and each nudge re-request) must still count against the request ceiling.
     */
    @Test fun `uncharged calls still count as requests`() {
        val b = budget(maxRequests = 2)
        b.recordWireCall(null) // turn 0 — no chargeEstimate ever ran for this one
        b.recordWireCall(null) // a nudge re-request inside the same turn
        assertEquals(2, b.requests())
        assertEquals(RunBudget.Limit.REQUESTS, b.exceededLimit())
    }

    /** The case tokens cannot see: a request-metered free tier. */
    @Test fun `many cheap calls trip requests while tokens look healthy`() {
        val b = budget(maxRequests = 10)
        repeat(10) { b.recordWireCall(50) }
        assertEquals(RunBudget.Limit.REQUESTS, b.exceededLimit())
        assertEquals("spend was trivial — only the request count saw this", 500, b.spentTokens())
    }

    /** The default: no cap set in Settings means requests never end a run. */
    @Test fun `no request cap means requests never end a run`() {
        val b = budget(maxRequests = null)
        repeat(1_000) { b.recordWireCall(10) }
        assertEquals(1_000, b.requests())
        assertNull(b.exceededLimit())
    }

    @Test fun `charging an estimate is not itself a request`() {
        val b = budget()
        b.chargeEstimate(500)
        assertEquals(0, b.requests())
    }

    // ── F1: wall-clock ────────────────────────────────────────────────────────

    @Test fun `trips on wall-clock even with no calls made`() {
        var now = 1_000L
        val b = budget(maxWallClockMs = 5_000, clock = { now })
        assertFalse(b.exceeded())
        now = 6_100L
        assertTrue(b.exceeded())
        assertEquals(RunBudget.Limit.WALL_CLOCK, b.exceededLimit())
    }

    @Test fun `elapsed and remaining time are measured from construction`() {
        var now = 10_000L
        val b = budget(maxWallClockMs = 60_000, clock = { now })
        now = 25_000L
        assertEquals(15_000L, b.elapsedMs())
        assertEquals(45_000L, b.remainingMs())
    }

    /** F2 leans on this: a stated retry wait longer than the run has left must not be slept off. */
    @Test fun `remaining time floors at zero rather than going negative`() {
        var now = 0L
        val b = budget(maxWallClockMs = 1_000, clock = { now })
        now = 9_999L
        assertEquals(0L, b.remainingMs())
    }

    // ── which limit reported ──────────────────────────────────────────────────

    @Test fun `no limit reported while every meter is under its ceiling`() {
        assertNull(budget().also { it.chargeEstimate(10) }.exceededLimit())
    }

    // ── 2026-08-26: cache-served prompt tokens are not new spend ──────────────

    @Test fun `a cache-served prompt slice is not charged`() {
        val b = budget()
        b.recordWireCall(totalTokens = 16_500, cachedTokens = 12_000)
        assertEquals(4_500, b.spentTokens())
        assertEquals(12_000, b.cachedTokens())
    }

    /**
     * The regression this fix exists for. Measured on the 2026-08-26 suite: a ~16.4k prompt of
     * which ~12.1k is the cached system block + tool schemas. Charging the full total made the
     * 200k cap a ~12-turn cap; charging only new tokens leaves the run alive to finish.
     */
    @Test fun `a well-cached run is no longer capped at about ten turns`() {
        val b = budget(maxRequests = 100)
        repeat(12) { b.recordWireCall(totalTokens = 16_400, cachedTokens = 12_100) }
        assertNull("a cached re-read must not exhaust the token cap", b.exceededLimit())
        assertEquals(12 * 4_300, b.spentTokens())
    }

    /** The request ceiling — not the token cap — is what still bounds a cheap looping run. */
    @Test fun `requests still bound a run whose prompt is entirely cache-served`() {
        val b = budget(maxRequests = 5)
        repeat(5) { b.recordWireCall(totalTokens = 16_000, cachedTokens = 16_000) }
        assertEquals(0, b.spentTokens())
        assertEquals(RunBudget.Limit.REQUESTS, b.exceededLimit())
    }

    /** "Entirely cache-served" is still a report, not a silent fallback to the guess. */
    @Test fun `a fully cached call still counts as reported usage`() {
        val b = budget()
        b.chargeEstimate(9_000)
        b.recordWireCall(totalTokens = 16_000, cachedTokens = 16_000)
        assertTrue(b.tokensAreReported())
        assertEquals(0, b.spentTokens())
    }

    /** A provider reporting nonsense (cached > total) must not credit the run tokens back. */
    @Test fun `cached tokens above the total cannot go negative`() {
        val b = budget()
        b.recordWireCall(totalTokens = 1_000, cachedTokens = 9_999)
        assertEquals(0, b.spentTokens())
    }

    @Test fun `an unreported cache figure charges the whole total`() {
        val b = budget()
        b.recordWireCall(totalTokens = 5_000, cachedTokens = null)
        assertEquals(5_000, b.spentTokens())
        assertEquals(0, b.cachedTokens())
    }

    // ── 2026-08-26: the single closing turn ───────────────────────────────────

    @Test fun `the closing turn can be claimed exactly once`() {
        val b = budget()
        assertTrue(b.beginClosing())
        assertFalse("a second trip must not buy another turn", b.beginClosing())
        assertTrue(b.isClosing())
    }

    @Test fun `a run that never trips a ceiling is never closing`() {
        assertFalse(budget().isClosing())
    }
}
