package com.aura.aura_ui.agent.llm

import ai.koog.prompt.executor.clients.retry.RetryConfig
import com.aura.aura_ui.agent.strategy.AgentContextConfig
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2 — one derived retry policy with a wall-clock ceiling.
 *
 * Two things were wrong and neither was what the design doc first assumed (see
 * [KoogRetryPatternTest] for the measurement that corrected it):
 *
 *  1. `AURA_RETRY_CONFIG` was a hardcoded free-tier constant — 6 attempts, 60 s max delay — with
 *     no bound on the TOTAL time spent asleep. `RetryConfig` can only cap a single delay.
 *  2. `AgentContextConfig.retryMaxAttempts` / `retryMaxTotalMs` looked like the live policy,
 *     contradicted it (4 vs 6), and had zero consumers. Promoting them to the single source is
 *     what stops the next person tuning the decoy.
 */
class AgentRetryPolicyTest {

    @Test fun `the config is the single source of the attempt count`() {
        val cfg = AgentContextConfig.DEFAULT.copy(retryMaxAttempts = 3, retryMaxTotalMs = 600_000)
        assertEquals(3, AgentRetryPolicy.configFor(cfg).maxAttempts)
    }

    @Test fun `attempts are trimmed until the worst-case sleep fits the wall-clock ceiling`() {
        // 1s + 2s + 4s + 8s + 16s = 31s of sleeping across 6 attempts. A 10s ceiling admits only
        // the attempts whose cumulative delay stays inside it: 1s + 2s + 4s = 7s over 4 attempts.
        val cfg = AgentContextConfig.DEFAULT.copy(retryMaxAttempts = 6, retryMaxTotalMs = 10_000)
        val policy = AgentRetryPolicy.configFor(cfg)
        assertTrue("must trim, not keep all 6", policy.maxAttempts < 6)
        assertTrue(
            "worst-case sleep must fit the ceiling",
            AgentRetryPolicy.worstCaseSleepMs(policy) <= 10_000,
        )
    }

    @Test fun `a generous ceiling leaves the configured attempts untouched`() {
        val cfg = AgentContextConfig.DEFAULT.copy(retryMaxAttempts = 4, retryMaxTotalMs = 600_000)
        assertEquals(4, AgentRetryPolicy.configFor(cfg).maxAttempts)
    }

    /** Rule 2: degrade, never refuse. A ceiling too small for even one delay still tries once. */
    @Test fun `an impossible ceiling still permits a single attempt`() {
        val cfg = AgentContextConfig.DEFAULT.copy(retryMaxAttempts = 6, retryMaxTotalMs = 1)
        assertEquals(1, AgentRetryPolicy.configFor(cfg).maxAttempts)
        assertEquals(0L, AgentRetryPolicy.worstCaseSleepMs(AgentRetryPolicy.configFor(cfg)))
    }

    @Test fun `the shipped default cannot outsleep the run's own wall-clock budget`() {
        val cfg = AgentContextConfig.DEFAULT
        // Nominal backoff ladder only. Server-directed waits bypass it; ServerRetryDelayTest pins
        // their separate ceiling.
        val worst = AgentRetryPolicy.worstCaseSleepMs(AgentRetryPolicy.configFor(cfg))
        assertTrue(
            "a single call must not be able to sleep away the whole run ($worst ms)",
            worst < cfg.runMaxWallClockMs,
        )
    }

    @Test fun `worst-case sleep counts the gaps between attempts, not one per attempt`() {
        // n attempts means n-1 delays. Getting this off by one is how a "bounded" policy
        // quietly exceeds its bound.
        val policy = AgentRetryPolicy.configFor(
            AgentContextConfig.DEFAULT.copy(retryMaxAttempts = 3, retryMaxTotalMs = 600_000),
        )
        val d0 = policy.initialDelay.inWholeMilliseconds
        val d1 = (d0 * policy.backoffMultiplier).toLong()
        assertEquals(d0 + d1, AgentRetryPolicy.worstCaseSleepMs(policy))
    }

    @Test fun `no single delay exceeds the configured max delay`() {
        val policy = AgentRetryPolicy.configFor(
            AgentContextConfig.DEFAULT.copy(retryMaxAttempts = 12, retryMaxTotalMs = 3_600_000),
        )
        assertTrue(policy.maxDelay >= policy.initialDelay)
        assertTrue(
            AgentRetryPolicy.worstCaseSleepMs(policy) <=
                policy.maxDelay.inWholeMilliseconds * (policy.maxAttempts - 1),
        )
    }

    @Test fun `retryable patterns are left as Koog ships them`() {
        // We deliberately do not narrow the pattern list: 400s already fail fast (measured in
        // KoogRetryPatternTest), so a bespoke list would add drift risk for no gain.
        assertEquals(
            RetryConfig.DEFAULT_PATTERNS.size,
            AgentRetryPolicy.configFor(AgentContextConfig.DEFAULT).retryablePatterns.size,
        )
    }

    @Test fun `initial delay is a real duration`() {
        val policy = AgentRetryPolicy.configFor(AgentContextConfig.DEFAULT)
        assertTrue(policy.initialDelay > 0.milliseconds)
    }
}
