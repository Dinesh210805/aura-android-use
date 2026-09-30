package com.aura.aura_ui.agent.strategy

import com.aura.aura_ui.agent.llm.AgentRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compaction threshold used to be a fixed `12_000` — a number tuned against Groq's 30k
 * tokens-per-minute RATE limit, then used as a CONTEXT threshold. Two different constraints, one
 * constant, wrong in both directions. See
 * `docs/superpowers/specs/2026-08-11-dynamic-provider-limits-REMINDER.md`.
 */
class AgentContextConfigTest {

    @Test fun `an unknown context window keeps exactly the historical behaviour`() {
        assertEquals(
            AgentContextConfig.FALLBACK_COMPACT_THRESHOLD,
            AgentContextConfig.forContextWindow(null).compactThresholdTokens,
        )
    }

    @Test fun `a nonsensical context window falls back rather than trusting it`() {
        assertEquals(
            AgentContextConfig.FALLBACK_COMPACT_THRESHOLD,
            AgentContextConfig.forContextWindow(0).compactThresholdTokens,
        )
        assertEquals(
            AgentContextConfig.FALLBACK_COMPACT_THRESHOLD,
            AgentContextConfig.forContextWindow(-1).compactThresholdTokens,
        )
    }

    /** The case that was silently making the agent forgetful for no reason. */
    @Test fun `a large window compacts far later than the old fixed threshold`() {
        val cfg = AgentContextConfig.forContextWindow(128_000)
        assertEquals(64_000, cfg.compactThresholdTokens)
        assertTrue(cfg.compactThresholdTokens > AgentContextConfig.FALLBACK_COMPACT_THRESHOLD)
    }

    @Test fun `a small window compacts earlier than the old fixed threshold`() {
        val cfg = AgentContextConfig.forContextWindow(16_000)
        assertEquals(8_000, cfg.compactThresholdTokens)
        assertTrue(cfg.compactThresholdTokens < AgentContextConfig.FALLBACK_COMPACT_THRESHOLD)
    }

    /** Below the floor there is no room left for a plan, a screen and a reply. */
    @Test fun `a tiny window is floored rather than compacting on every turn`() {
        assertEquals(
            AgentContextConfig.MIN_COMPACT_THRESHOLD,
            AgentContextConfig.forContextWindow(2_000).compactThresholdTokens,
        )
    }

    /**
     * P14 — the failure the window-scaling fix introduced on the other axis.
     *
     * Gemini publishes `inputTokenLimit = 1_048_576`, so a floor-only derivation gave a ~524k
     * threshold: a prompt size this agent never reaches, which switched compaction OFF entirely
     * on the largest-window models. A ceiling is what keeps the mechanism alive at both ends.
     */
    @Test fun `a huge window is capped rather than disabling compaction outright`() {
        val cfg = AgentContextConfig.forContextWindow(1_048_576)
        assertEquals(AgentContextConfig.MAX_COMPACT_THRESHOLD, cfg.compactThresholdTokens)
        assertTrue(
            "a 1M window must not derive a threshold no real prompt can reach",
            cfg.compactThresholdTokens < 1_048_576 / 2,
        )
    }

    @Test fun `the cap applies to every window above it, not just the largest`() {
        assertEquals(
            AgentContextConfig.MAX_COMPACT_THRESHOLD,
            AgentContextConfig.forContextWindow(200_000).compactThresholdTokens,
        )
        assertEquals(
            AgentContextConfig.MAX_COMPACT_THRESHOLD,
            AgentContextConfig.forContextWindow(2_000_000).compactThresholdTokens,
        )
    }

    /** Floor below fallback below ceiling — otherwise one bound silently swallows another. */
    @Test fun `the three bounds are ordered`() {
        assertTrue(
            AgentContextConfig.MIN_COMPACT_THRESHOLD < AgentContextConfig.FALLBACK_COMPACT_THRESHOLD,
        )
        assertTrue(
            AgentContextConfig.FALLBACK_COMPACT_THRESHOLD < AgentContextConfig.MAX_COMPACT_THRESHOLD,
        )
    }

    @Test fun `only the compaction threshold is derived, other tunables are untouched`() {
        val cfg = AgentContextConfig.forContextWindow(128_000)
        assertEquals(AgentContextConfig.DEFAULT.runMaxWallClockMs, cfg.runMaxWallClockMs)
        assertEquals(AgentContextConfig.DEFAULT.retryMaxAttempts, cfg.retryMaxAttempts)
        assertEquals(AgentContextConfig.DEFAULT.settleQuietMs, cfg.settleQuietMs)
    }

    /**
     * 2026-09-22. The shipped default must be "no request ceiling": the caps that lived here
     * killed 8 of 25 runs in the 2026-09-21 bench, and any constant re-introduced here would be
     * wrong for somebody (40 kills real tasks, 150 burns a third of a 500/day free tier on one
     * runaway, a paid key wants neither). A cap now arrives only from Settings, per endpoint.
     */
    @Test fun `no per-run request ceiling ships by default`() {
        assertNull(AgentContextConfig.DEFAULT.runMaxRequests)
        assertNull(AgentContextConfig.forContextWindow(1_048_576).runMaxRequests)
    }

    /**
     * The retry ladder is the only thing absorbing rate limits now that anticipatory pacing is
     * gone, so the total-sleep ceiling must be wide enough to admit every configured attempt.
     * A ceiling that silently trims the ladder back would reintroduce the failure quietly.
     */
    @Test fun `the retry ceiling admits every configured attempt`() {
        val cfg = AgentContextConfig.DEFAULT
        val policy = AgentRetryPolicy.configFor(cfg)
        assertEquals(cfg.retryMaxAttempts, policy.maxAttempts)
        assertTrue(AgentRetryPolicy.worstCaseSleepMs(policy) <= cfg.retryMaxTotalMs)
    }

    /**
     * Server-directed waits ("please retry in 41s") are budgeted separately from the backoff
     * ladder. Sharing one constant meant two Gemini free-tier 429s exhausted the run's patience.
     */
    @Test fun `server-directed waits get their own, larger budget`() {
        assertTrue(
            AgentContextConfig.DEFAULT.serverWaitBudgetMs > AgentContextConfig.DEFAULT.retryMaxTotalMs,
        )
    }
}
