package com.aura.aura_ui.agent.llm

import ai.koog.prompt.executor.clients.retry.DefaultRetryAfterExtractor
import ai.koog.prompt.executor.clients.retry.RetryAfterExtractor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Makes Koog's retry ladder wait the time the provider asked for, within a per-client ceiling.
 *
 * Koog's `RetryingLLMClient` asks its [RetryAfterExtractor] first and, when that returns a
 * duration, sleeps exactly that — **uncapped**, bypassing `RetryConfig.maxDelay`. Its default
 * extractor cannot read Gemini's "Please retry in 41.2s", so every Gemini 429 fell to the
 * 1 s → 2 s → 4 s ladder and died inside the closed window. This reads the wait via
 * [QuotaExhaustion.retryDelayMs] and delegates everything else to Koog's default.
 *
 * ### The ceiling
 *
 * Server-directed sleep is counted across this client's life (one client per agent run —
 * `AuraAgent` builds it per run), and a wait that would push the total past [ceilingMs] returns
 * [Duration.ZERO] instead. Koog then spends the remaining attempts immediately, they 429 at once,
 * and the run ends with `QuotaExhaustion`'s spoken reason — failing fast instead of sleeping
 * through a wait that cannot fit.
 *
 * [ceilingMs] is `AgentContextConfig.serverWaitBudgetMs` (10 min), **not** `retryMaxTotalMs`. It
 * used to be the latter, and the two quantities have nothing to do with each other: a 90 s ladder
 * budget meant two Gemini free-tier 429s at ~35 s each exhausted it, after which every later 429 in
 * the run failed fast. Harmless while runs died at turn 11 on a token ceiling; fatal to a run of a
 * hundred turns, which is now the point.
 *
 * This is the one place AURA is still stricter than Claude Code, which lets `Retry-After` bypass
 * its delay cap outright ("honoring it is correct"). A bound is kept because AURA is holding
 * somebody's phone awake while it sleeps.
 *
 * ponytail: static ceiling, not `RunBudget.remainingMs()` — a 429 near the end of a run can still
 * wake past the run's deadline. Thread the budget in if that shows up on device.
 */
internal class ServerRetryDelay(private val ceilingMs: Long) : RetryAfterExtractor {

    private var sleptMs = 0L

    override fun extract(message: String): Duration? {
        val serverMs = QuotaExhaustion.retryDelayMs(message)
            ?: return DefaultRetryAfterExtractor.extract(message)
        val waitMs = serverMs + MARGIN_MS
        synchronized(this) {
            if (sleptMs + waitMs > ceilingMs) return Duration.ZERO
            sleptMs += waitMs
        }
        return waitMs.milliseconds
    }

    private companion object {
        /** The quoted wait is when the window reopens; landing exactly on it races the refill. */
        const val MARGIN_MS = 1_000L
    }
}
