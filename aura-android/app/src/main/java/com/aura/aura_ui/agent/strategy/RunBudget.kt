package com.aura.aura_ui.agent.strategy

/**
 * Per-run safety net, and the run's token accounting. When [exceeded] the strategy routes to a
 * clean spoken abort instead of grinding on.
 *
 * ### Why tokens are counted here but end nothing (2026-09-22)
 *
 * This class used to carry a cumulative token ceiling, and it was the single largest cause of
 * failed runs: **8 of the 25 tasks** in the 2026-09-21 bench died on it, several after four taps.
 * The number it summed is not a thing:
 *
 * A chat API re-sends the entire conversation on every turn. So summing each request's
 * `prompt_tokens` across a run adds the same system block and the same tool schemas over and over —
 * it grows quadratically in turns while the conversation itself grows linearly. After ten turns the
 * sum read 200k on a conversation that was 25k long and in no danger from any context window. The
 * ceiling was a turn limit wearing a token's clothes.
 *
 * Worse, it was a **different** turn limit per provider. [recordWireCall] charges
 * `total - cached`, and `cached_tokens` is an optional usage field: Gemini reports ~14k of an 18k
 * prompt served from its own cache, Groq and most OpenAI-compat proxies report nothing. Identical
 * work, identical model, ~10x difference in how far the agent got, decided by whether the provider
 * bothered to fill in a JSON field.
 *
 * Both of the agents worth copying here treat tokens the same way this class now does. Claude Code
 * sums them in `cost-tracker.ts` and no code path anywhere reads that sum to stop anything — it
 * exists to print `/cost`. Its *other* token budget, in `query/tokenBudget.ts`, is a continuation
 * nudge that sends the agent back in ("Keep working — do not summarize") while it is under budget.
 * Context pressure is handled where it actually lives, per request, by compaction.
 *
 * So: [spentTokens], [cachedTokens] and [estimatedTokens] are the run's instruments, reported to the
 * trace and read by the eval harness. They are not a verdict.
 *
 * ### What does end a run
 *
 * **Wall-clock**, always; **requests**, only when the user opted into a cap
 * ([AgentContextConfig.runMaxRequests]). Both cost nothing to count, are exact, and need no
 * cooperation from the provider — which is why they behave identically on an endpoint nobody has
 * seen. No provider name appears anywhere in this class.
 *
 * ### Why counting happens at the wire
 *
 * [recordWireCall] is invoked from the HTTP tap, which every LLM call provably passes through.
 * [chargeEstimate] is invoked from the strategy, which does **not** see every call: it charges only
 * in the tool-result node, so the turn-0 request and each `EmptyTurnPolicy` nudge re-request would
 * go uncounted. Counting requests anywhere but the wire produces a "request ceiling" that silently
 * admits more calls than it claims.
 *
 * ### Estimate now, truth later
 *
 * The pre-flight estimate exists because the compaction decision must be made before the request
 * does — real usage cannot inform a choice that precedes it. But the estimate is only a *fallback*:
 * as soon as any call reports real usage, reported totals supersede the guesses entirely rather
 * than adding to them. A provider that reports nothing keeps the estimate in charge, so the meter
 * degrades instead of reading zero (Rule 2).
 *
 * Thread-safety: [chargeEstimate] runs on the agent coroutine while [recordWireCall] is delivered
 * on an OkHttp thread, so every mutation is synchronized. (The class this replaced documented
 * itself as single-threaded and would have been wrong the moment F3 wired usage in.)
 */
class RunBudget(
    private val maxRequests: Int?,
    private val maxWallClockMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Which ceiling ended the run — carried into the spoken reason so the abort is honest. */
    enum class Limit { REQUESTS, WALL_CLOCK }

    private val startedAtMs: Long = nowMs()

    private val lock = Any()
    private var estimatedTokens = 0
    private var reportedTokens = 0
    private var cachedTokensSeen = 0
    private var anyReported = false
    private var requests = 0
    private var closingConsumed = false

    /**
     * Record the pre-flight estimate for an upcoming call. Deliberately NOT a request: requests are
     * counted at the wire, where none can be missed. Non-positive estimates are ignored.
     */
    fun chargeEstimate(tokens: Int) = synchronized(lock) {
        estimatedTokens += tokens.coerceAtLeast(0)
    }

    /**
     * Record one call that actually went over the wire, with the provider's reported total if it
     * sent one. A null (or non-positive) total means this provider reported no usage, so the
     * estimate stays in charge — "not reported" must never be read as zero.
     *
     * Every attempt of a retried call arrives here separately, and every one of them is counted:
     * each attempt that reached the provider really did consume its tokens and its request.
     *
     * ### Why cached prompt tokens are not charged (2026-08-26)
     *
     * [cachedTokens] is the slice of the prompt the provider served from its own cache — for us,
     * overwhelmingly the system block and the ~12.5k of tool schemas that ride every single
     * request unchanged. Charging them made the cap measure **turns**, not spend: measured on the
     * 2026-08-26 suite, turn-0 prompts were 16,290–16,500 tokens of which 12,066–16,144 were
     * cached, so a 200k cap died at ~10 turns no matter how cheap the run actually was. Two of the
     * suite's three budget deaths were tasks that had already found their answer and were still
     * mid-sentence (task 14 had "Chennai… Now 35° Cloudy" in context when the meter tripped).
     *
     * So this charges **new** tokens only. The honest reading of [spentTokens] is now "tokens this
     * run caused the provider to process for the first time". The safety property the old
     * behaviour was accidentally providing — a bound on how many turns a run may take — is not
     * lost: [maxRequests] provides it exactly, cheaply and without lying about tokens.
     */
    fun recordWireCall(totalTokens: Int?, cachedTokens: Int? = null) = synchronized(lock) {
        requests += 1
        val actual = totalTokens?.takeIf { it > 0 } ?: return@synchronized
        val cached = (cachedTokens ?: 0).coerceIn(0, actual)
        reportedTokens += actual - cached
        cachedTokensSeen += cached
        // A call that was *entirely* cache-served still happened and still counts as reported —
        // otherwise a run of perfect cache hits would silently fall back to the estimate.
        anyReported = true
    }

    /** Reported totals when the provider gives them, the estimate when it gives nothing. */
    fun spentTokens(): Int = synchronized(lock) { if (anyReported) reportedTokens else estimatedTokens }

    /** True when [spentTokens] is the provider's own accounting rather than our guess. */
    fun tokensAreReported(): Boolean = synchronized(lock) { anyReported }

    /**
     * The running pre-flight estimate, kept visible alongside the reported figure so the gap
     * between them is observable. Measured on device it is large and structurally so: the estimate
     * sums `prompt.messages`, but tool schemas ride in the request's `tools` field, so the biggest
     * component of the prompt — and the one compaction cannot shrink — is invisible to it.
     */
    fun estimatedTokens(): Int = synchronized(lock) { estimatedTokens }

    /**
     * Prompt tokens the provider served from its own cache and this budget therefore did not
     * charge. Surfaced so the gap between "what the provider billed" and "what we counted" stays
     * observable in the trace rather than being an invisible policy choice.
     */
    fun cachedTokens(): Int = synchronized(lock) { cachedTokensSeen }

    fun requests(): Int = synchronized(lock) { requests }

    /**
     * Claim the run's single closing turn. Returns true exactly once, and only when a ceiling has
     * actually been reached.
     *
     * ### Why a run gets one call past its ceiling
     *
     * A run that trips its budget has usually already done the work — measured on the 2026-08-26
     * suite, the weather task had the answer in context and spent its last four turns on
     * `mark_step` bookkeeping before the meter tripped, and the user heard "I used up my budget"
     * instead of "35 and cloudy". Throwing at the ceiling discards that work. One tool-free
     * request, whose only job is to state what the run already knows, converts that loss into an
     * answer for a few hundred completion tokens.
     *
     * It is claimed rather than merely allowed so the escape hatch cannot become the loop: the
     * second trip has nothing left to claim and ends the run.
     *
     * [Limit.WALL_CLOCK] used to be excluded, on the reasoning that a run already over its time
     * bound cannot honestly ask for more time. That was right when wall-clock was the loosest of
     * three ceilings and essentially never tripped; now that it is the only always-on one, excluding
     * it would retire this mechanism entirely. The closing turn is one tool-free request — a couple
     * of seconds against a 30-minute bound — and it is the difference between speaking the answer
     * the run already found and announcing that time ran out.
     */
    fun beginClosing(): Boolean = synchronized(lock) {
        if (closingConsumed) return@synchronized false
        closingConsumed = true
        true
    }

    /** True once [beginClosing] has been claimed — the run is on its final, tool-free turn. */
    fun isClosing(): Boolean = synchronized(lock) { closingConsumed }

    fun elapsedMs(): Long = nowMs() - startedAtMs

    /** How long this run may still legitimately take. F2 compares a stated retry wait against it. */
    fun remainingMs(): Long = (maxWallClockMs - elapsedMs()).coerceAtLeast(0)

    /**
     * The first ceiling reached, or null while every meter is still under its bound.
     *
     * Tokens are deliberately absent: they are counted (see [spentTokens]) and reported, and they
     * end nothing. See this class's KDoc for why a cumulative token sum cannot be a ceiling.
     * [maxRequests] is null unless the user opted into one.
     */
    fun exceededLimit(): Limit? {
        val calls = requests()
        return when {
            maxRequests != null && calls >= maxRequests -> Limit.REQUESTS
            elapsedMs() >= maxWallClockMs -> Limit.WALL_CLOCK
            else -> null
        }
    }

    fun exceeded(): Boolean = exceededLimit() != null
}
