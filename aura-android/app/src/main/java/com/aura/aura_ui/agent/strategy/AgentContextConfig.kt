package com.aura.aura_ui.agent.strategy

/**
 * Single source of every action-plane context-discipline tunable. Defaults are
 * provider-agnostic — they bound tokens and pacing, never key off a model id. Keeping
 * them here (not scattered as magic numbers) is what lets the whole feature stay tunable
 * and honest about its knobs.
 */
data class AgentContextConfig(
    /**
     * Optional per-run ceiling on LLM calls. **Null (the default) means no ceiling** — the user
     * sets one per endpoint in Settings → Brain if they want their free tier's daily quota
     * protected from a runaway run.
     *
     * ### Why there is no default
     *
     * There used to be a cumulative token ceiling here (200k) and a request ceiling (40), and
     * between them they killed **8 of 25** runs in the 2026-09-21 bench — tasks that had done four
     * taps and were nowhere near finished. The token one was the worse of the two and is gone
     * entirely (see [RunBudget]); this one survives only as an opt-in, because any constant picked
     * here is wrong for somebody: 40 kills real tasks, 150 burns a third of a 500-request daily
     * quota on one runaway, and a paid key wants neither.
     *
     * What actually guards a runaway does not need a number from the user: `ActionGuard` blocks a
     * repeated action on an unchanged screen (it can tell looping from progress, which a counter
     * cannot), [runMaxWallClockMs] bounds anything that grinds, and — unlike a coding agent running
     * unattended — AURA runs with the screen on and a pause pill the human can hit.
     */
    val runMaxRequests: Int? = null,
    /**
     * Per-run wall-clock ceiling — the single always-on backstop now that tokens and requests are
     * not ceilings.
     *
     * On a rate-limited tier this bounds requests for free and without knowing the tier: a provider
     * allowing 15 requests/minute cannot deliver more than ~450 inside 30 minutes, and AURA's own
     * pace (a tap plus a screen settle per turn) makes ~250 the realistic figure. On a paid key
     * with no rate limit it bounds by time instead, which is the honest thing to bound a run that
     * is driving somebody's phone by.
     */
    val runMaxWallClockMs: Long = 30 * 60_000,
    /**
     * When the last call's prompt exceeds this, compress history before the next call.
     *
     * The default is the historical value and is deliberately conservative; prefer
     * [forContextWindow], which scales it to the model actually being used. See that factory for
     * why a fixed number here was wrong in both directions.
     */
    val compactThresholdTokens: Int = FALLBACK_COMPACT_THRESHOLD,
    /** On compaction, keep this many most-recent messages verbatim (older are summarized). */
    val compactKeepLastMessages: Int = 6,
    /** Screen counts as "settled" after this long with no accessibility event. */
    val settleQuietMs: Long = 350,
    /** Hard cap on how long to wait for the screen to settle after a gesture. */
    val settleMaxMs: Long = 1_500,
    /** Poll interval while waiting for the screen to settle. */
    val settlePollMs: Long = 50,
    /**
     * Max LLM retry attempts. Read by [com.aura.aura_ui.agent.llm.AgentRetryPolicy], which builds
     * the live `RetryConfig` from these two fields. Until F2 they had **no consumers at all** while
     * a second, contradictory policy (6 attempts) sat hardcoded in the provider — so tuning this
     * field did nothing, which is the worst way for a knob to behave.
     *
     * 10 matches Claude Code's `DEFAULT_MAX_RETRIES`, and for the same reason: with no anticipatory
     * pacing left in the stack (see [com.aura.aura_ui.agent.llm.ServerRetryDelay]), the retry ladder
     * is the *only* thing absorbing a free tier's rate limits. Half-copying that design — dropping
     * the pacer while keeping a 4-attempt ladder — would be worse than either whole design.
     */
    val retryMaxAttempts: Int = 10,
    /**
     * Overall wall-clock cap across all retries of a single call. `RetryConfig` can only bound one
     * delay, so without this an attempt ladder could sleep for minutes inside what the rest of the
     * system sees as a single call — invisible to [runMaxWallClockMs], which is only consulted
     * between turns. Attempts are trimmed until the worst-case ladder fits inside this.
     *
     * Sized to admit all [retryMaxAttempts]: 1+2+4+8+16+32+60+60+60 = 243 s on the 2× ladder.
     */
    val retryMaxTotalMs: Long = 300_000,
    /**
     * Cumulative budget, per run, for waits the **server itself asked for** ("please retry in 41s").
     *
     * Split out from [retryMaxTotalMs] because one constant was doing two unrelated jobs, and the
     * smaller of the two needs sized it wrong for the other: two Gemini free-tier 429s at ~35 s each
     * exhausted the 90 s ladder budget, after which every later 429 in that run failed fast. That
     * was invisible while runs died at turn 11 and is guaranteed to bite a run of a hundred turns.
     *
     * Generous on purpose. Claude Code lets `Retry-After` bypass its delay cap outright — its
     * comment reads "honoring it is correct" — because the server knows when its window reopens and
     * we do not.
     */
    val serverWaitBudgetMs: Long = 10 * 60_000,
) {
    companion object {
        val DEFAULT = AgentContextConfig()

        /**
         * The value this used to be hardcoded to, kept as the fallback for models whose provider
         * publishes no context window (OpenAI and Anthropic do not, and no custom endpoint can).
         */
        const val FALLBACK_COMPACT_THRESHOLD = 12_000

        /** Compact at half the window: room to grow, and room left for the reply. */
        const val COMPACT_WINDOW_FRACTION = 0.5

        /** Never compact below this, however small the window claims to be. */
        const val MIN_COMPACT_THRESHOLD = 4_000

        /**
         * Never defer compaction beyond this, however large the window claims to be.
         *
         * A floor alone was only half the bound. Gemini publishes `inputTokenLimit = 1_048_576`,
         * so window-scaling alone derived a ~524k threshold — a prompt size this agent never
         * reaches, which switched compaction OFF entirely on exactly the models where history
         * grows longest. Fixing a rate-limit constant by scaling to the window had moved the
         * failure to the other axis instead of removing it.
         *
         * The number is a property of THIS agent's prompt shape, not of any provider: a
         * screen-automation loop carries a system prompt, a tool inventory, a plan and a
         * screenshot, and past roughly this much accumulated history the older turns have
         * negligible value against their per-call cost. It is deliberately equal to half of the
         * common 128k window, so every model at or below that size is unaffected by the cap.
         */
        const val MAX_COMPACT_THRESHOLD = 64_000

        /**
         * Scale the compaction threshold to the model actually in use.
         *
         * ### Why the old constant was wrong in both directions
         *
         * `12_000` was tuned against Groq's 30k tokens-per-minute ceiling — a RATE limit, a
         * property of the account tier. It was then used as a CONTEXT threshold, a property of the
         * model. Those are different constraints, and one number cannot serve both: on a 128k-window
         * model it threw away context the run had already paid for and could have kept, making the
         * agent needlessly forgetful; on a genuinely small window it was no protection at all.
         *
         * Expressing it as a fraction fixes the context half honestly, but a fraction needs BOTH
         * bounds: see [MAX_COMPACT_THRESHOLD] for the failure an unbounded fraction introduced on
         * million-token windows. The rate half — pacing calls against an observed budget — is a
         * separate mechanism, deliberately not attempted here; the design lives in
         * `docs/superpowers/specs/2026-08-13-provider-limits-and-capabilities-design.md`.
         *
         * @param contextWindow the model's input limit in tokens, or null when unknown.
         */
        fun forContextWindow(contextWindow: Int?): AgentContextConfig {
            val threshold = contextWindow
                ?.takeIf { it > 0 }
                ?.let {
                    (it * COMPACT_WINDOW_FRACTION).toInt()
                        .coerceIn(MIN_COMPACT_THRESHOLD, MAX_COMPACT_THRESHOLD)
                }
                ?: FALLBACK_COMPACT_THRESHOLD
            return AgentContextConfig(compactThresholdTokens = threshold)
        }
    }
}
