package com.aura.aura_ui.agent.llm

import ai.koog.prompt.executor.clients.retry.RetryConfig
import com.aura.aura_ui.agent.strategy.AgentContextConfig
import kotlin.time.Duration.Companion.seconds

/**
 * The agent's retry policy, **derived** from [AgentContextConfig] rather than hardcoded.
 *
 * ### What was actually wrong
 *
 * The policy used to be a private constant in [OpenAiCompatProvider] whose KDoc said outright
 * *"Tuned for FREE-TIER limits"* — 6 attempts, 60 s max delay — while `AgentContextConfig` carried
 * `retryMaxAttempts = 4` and `retryMaxTotalMs = 90_000` with **zero consumers**. Two policies, one
 * of them a decoy that read like the live one and contradicted it. Anyone tuning retries would have
 * tuned the dead one. This makes the config the single source and deletes the ambiguity.
 *
 * ### The bound that was missing
 *
 * `RetryConfig` can cap a *single* delay ([RetryConfig.maxDelay]) but has no notion of the total
 * time a call may spend asleep. Six attempts on a 2× ladder is 1+2+4+8+16 = 31 s of sleeping inside
 * what the rest of the system sees as one call — and the run's own wall-clock ceiling cannot help,
 * because it is only consulted between turns. [configFor] therefore trims the attempt count until
 * the worst case fits `retryMaxTotalMs`.
 *
 * ### What this deliberately does NOT do
 *
 * It does not narrow [RetryConfig.DEFAULT_PATTERNS]. The design doc assumed a 400 rode the same
 * ladder as a 429 and cost a doomed run minutes; that was measured and is **false** — Koog's
 * defaults retry only 429/500/502/503/504/529 plus transient transport keywords, so hard client
 * errors already fail fast. See `KoogRetryPatternTest`, which pins that behaviour so a Koog upgrade
 * that widens the defaults fails a test instead of silently costing every doomed run a minute.
 *
 * Nothing here keys off a provider, a host, or a model id.
 */
object AgentRetryPolicy {

    /** First backoff step. Subsequent delays multiply by [BACKOFF_MULTIPLIER] up to [MAX_DELAY]. */
    private val INITIAL_DELAY = 1.seconds

    /**
     * Ceiling on any single delay. A rate-limit window commonly refills on the order of a minute,
     * so a longer single sleep buys nothing the caller would not rather be told about.
     */
    private val MAX_DELAY = 60.seconds

    private const val BACKOFF_MULTIPLIER = 2.0

    /** Spread simultaneous retries so a burst does not re-collide on the same tick. */
    private const val JITTER_FACTOR = 0.2

    /**
     * Build the retry policy for [config], trimming attempts until the worst-case total sleep fits
     * `config.retryMaxTotalMs`. Always yields at least one attempt: a ceiling too small for even
     * the first delay must still let the call happen (Rule 2 — degrade, never refuse).
     */
    fun configFor(config: AgentContextConfig): RetryConfig {
        val attempts = affordableAttempts(config.retryMaxAttempts, config.retryMaxTotalMs)
        return RetryConfig(
            maxAttempts = attempts,
            initialDelay = INITIAL_DELAY,
            maxDelay = MAX_DELAY,
            backoffMultiplier = BACKOFF_MULTIPLIER,
            jitterFactor = JITTER_FACTOR,
            // Its own budget, not retryMaxTotalMs: a server-directed wait and a backoff-ladder
            // worst case are different quantities, and sharing one constant sized for the ladder
            // made the run give up on quota after ~2 stated waits. See AgentContextConfig.
            retryAfterExtractor = ServerRetryDelay(config.serverWaitBudgetMs),
        )
    }

    /**
     * Worst-case milliseconds spent asleep across a whole call under [policy].
     *
     * `n` attempts means `n - 1` delays — the gaps between attempts, not one per attempt. Getting
     * that off by one is exactly how a policy that claims to be bounded quietly exceeds its bound.
     * Jitter is excluded: Koog's jitter only spreads a delay around its nominal value, so the
     * nominal sum is the figure worth bounding.
     */
    fun worstCaseSleepMs(policy: RetryConfig): Long =
        sleepForAttempts(policy.maxAttempts, policy.initialDelay.inWholeMilliseconds, policy.maxDelay.inWholeMilliseconds)

    private fun affordableAttempts(requested: Int, ceilingMs: Long): Int {
        val initialMs = INITIAL_DELAY.inWholeMilliseconds
        val maxMs = MAX_DELAY.inWholeMilliseconds
        var affordable = 1
        for (n in 2..requested.coerceAtLeast(1)) {
            if (sleepForAttempts(n, initialMs, maxMs) > ceilingMs) break
            affordable = n
        }
        return affordable
    }

    private fun sleepForAttempts(attempts: Int, initialMs: Long, maxDelayMs: Long): Long {
        var total = 0L
        var delay = initialMs
        repeat((attempts - 1).coerceAtLeast(0)) {
            total += delay.coerceAtMost(maxDelayMs)
            delay = (delay * BACKOFF_MULTIPLIER).toLong()
        }
        return total
    }

}
