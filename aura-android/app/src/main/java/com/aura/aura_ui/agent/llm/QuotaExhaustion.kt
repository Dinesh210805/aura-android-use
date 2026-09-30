package com.aura.aura_ui.agent.llm

/**
 * Turns a provider refusal into something worth saying out loud — a rate/quota exhaustion, or a
 * model the provider says is overloaded.
 *
 * ### What this is NOT
 *
 * Not another retry layer. [OpenAiCompatProvider] wraps every client in Koog's
 * `RetryingLLMClient` with [AgentRetryPolicy]. This file used to claim that ladder honoured the
 * server's wait; for Gemini it never did — Koog's extractor cannot read "retry in Ns" (see
 * [retryDelayMs]). [ServerRetryDelay] now supplies that. A 429'd request is rejected rather than
 * counted, so the retries themselves do not burn quota.
 *
 * ### The actual gap
 *
 * Once those attempts are spent the run ends `failed` and **nobody says why**. The user is
 * left watching a phone that stopped, with no idea that their AI provider's free-tier limit
 * is the reason or when it clears — even though the 429 body states both verbatim
 * (`Please retry in 41.235709765s`). Two of five captured runs died exactly this way.
 *
 * ### Why string matching
 *
 * Deliberate. The failure surfaces through Koog's client wrapped in whatever the transport
 * threw, so there is no stable exception type to key on across four providers. The markers
 * below are phrases every OpenAI-wire provider emits for this condition.
 */
object QuotaExhaustion {

    private val MARKERS = listOf(
        "resource_exhausted",
        "exceeded your current quota",
        "quota exceeded",
        "rate limit",
        "too many requests",
    )

    /**
     * The provider is up, the key is fine, and the **model** is refusing work — Gemini's
     * `503 {"status":"UNAVAILABLE"}` / "experiencing high demand", Anthropic's `overloaded_error`,
     * OpenAI's 503 "server is overloaded".
     *
     * Kept apart from [MARKERS] deliberately: this is not a quota problem and must never be spoken
     * as one. Telling someone their quota ran out when the real answer is "this model is busy, pick
     * another" sends them to check billing pages that are perfectly healthy — which is exactly what
     * happened on 2026-09-22, when three of these inside one run surfaced only as the generic
     * "I couldn't reach your AI provider" and cost an evening chasing an unrelated commit.
     *
     * Matched on the provider's own phrasing plus the machine-readable status, not on the bare word
     * "unavailable", which is common enough in unrelated error prose to false-positive.
     */
    private val OVERLOADED = Regex(
        """experiencing high demand|is overloaded|overloaded_error|"status"\s*:\s*"UNAVAILABLE"|"code"\s*:\s*503""",
        RegexOption.IGNORE_CASE,
    )

    /** True when the provider says the model itself is too busy right now. */
    fun isOverloaded(text: String?): Boolean =
        !text.isNullOrBlank() && OVERLOADED.containsMatchIn(text)

    /**
     * Which of the three very different things a 429 can mean. The status code cannot tell them
     * apart — only the body can — and the difference is the whole value of the spoken message:
     * "wait forty seconds", "come back tomorrow" and "retrying will never work" are different
     * instructions to a user, and giving the first when the truth is the second sends them into a
     * try-fail-try-fail loop.
     */
    enum class Kind { PER_MINUTE, PER_DAY, OUT_OF_CREDIT }

    /**
     * Gemini names the violated quota outright in its 429 body, e.g.
     * `"quotaId": "GenerateRequestsPerDayPerProjectPerModel"`. The suffix is the tell. Matching the
     * quota id (rather than any prose) keeps this off the message text, which is free to be
     * reworded at any time.
     */
    private val PER_DAY = Regex("""per[_\s-]?day""", RegexOption.IGNORE_CASE)

    /**
     * OpenAI's billing exhaustion — distinct from rate limiting despite sharing the 429 and no
     * amount of waiting fixes it.
     *
     * Deliberately matched on the machine-readable codes ONLY. The obvious prose tell, "please
     * check your plan and billing details", is **not** usable: Gemini emits that exact sentence on
     * an ordinary per-minute token-rate 429 (see `QuotaExhaustionTest.geminiBody`, captured from a
     * device). Keying on it would tell a user whose window reopens in 41 seconds that their account
     * is empty.
     */
    private val OUT_OF_CREDIT = Regex("""insufficient_quota|billing_not_active""", RegexOption.IGNORE_CASE)

    /**
     * A stated wait at or under this is transient whatever quota name the body mentions. Gemini
     * free-tier bodies can list several violations at once, so a "PerDay" string may appear beside
     * the per-minute quota that actually tripped — and of the two possible mistakes, sending
     * someone away until tomorrow when they could retry in a minute is much the worse.
     */
    private const val SHORT_WAIT_MS = 5 * 60 * 1000L

    /**
     * Classify a provider refusal. Order matters: out-of-credit first, because its wording overlaps
     * both other kinds, and a per-day reading of it would promise a reset that never comes.
     */
    fun kindOf(text: String?): Kind {
        if (text.isNullOrBlank()) return Kind.PER_MINUTE
        val statedWaitMs = retryDelayMs(text)
        return when {
            OUT_OF_CREDIT.containsMatchIn(text) -> Kind.OUT_OF_CREDIT
            PER_DAY.containsMatchIn(text) &&
                (statedWaitMs == null || statedWaitMs > SHORT_WAIT_MS) -> Kind.PER_DAY
            else -> Kind.PER_MINUTE
        }
    }

    /** The same wait as a structured field in Gemini's 429 details: `"retryDelay": "41s"`. */
    private val RETRY_DELAY_FIELD = Regex(""""retryDelay"\s*:\s*"([0-9]+(?:\.[0-9]+)?)s"""")

    private val RETRY_SECONDS = Regex("""retry in ([0-9]+(?:\.[0-9]+)?)s""", RegexOption.IGNORE_CASE)

    /**
     * The wait the provider asked for, in milliseconds, or null when the text names none.
     *
     * Exists because Koog's own `DefaultRetryAfterExtractor` only knows "retry after N second",
     * "retry-after: N", "wait N second" and "try again in Ns" — Gemini's "Please retry in 41.2s"
     * matches none of them, so every Gemini 429 fell back to a 1 s → 2 s → 4 s ladder that was
     * guaranteed to hit the same closed window (2026-09-14: four retries inside 13 s against a
     * "retry in 30.8s", task dead with `quota`). See [ServerRetryDelay].
     */
    fun retryDelayMs(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val seconds = (RETRY_SECONDS.find(text) ?: RETRY_DELAY_FIELD.find(text))
            ?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: return null
        return (seconds * 1000).toLong()
    }

    /**
     * Does this text carry a provider rate/quota refusal?
     *
     * Exposed for readers that hold the response body rather than a throwable — notably the eval
     * cockpit, which decides whether a finished-but-empty run was the agent failing or the free
     * tier running out. That distinction is the difference between scoring a task `fail` and
     * scoring it `invalid`, so it must key off the same markers this class already trusts rather
     * than a second, drifting copy of them.
     */
    fun isQuotaFailure(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val low = text.lowercase()
        return MARKERS.any { it in low }
    }

    /**
     * A sentence to speak, or null when this failure is not a quota/rate exhaustion.
     *
     * Walks the whole cause chain: the 429 body is rarely the top-level message.
     */
    fun spokenReasonFor(throwable: Throwable?): String? {
        val text = generateSequence(throwable) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .ifBlank { return null }

        // Before the quota guard: an overloaded model carries none of the quota MARKERS, so it
        // would fall straight out of here as null and be spoken as the generic "couldn't reach
        // your provider" — the failure this whole file exists to prevent, in a second costume.
        if (isOverloaded(text)) {
            return "Your AI provider says that model is busy right now — it's getting more demand " +
                "than it can serve. That usually clears on its own in a few minutes, or you can " +
                "switch to a different model in Settings and carry on straight away."
        }

        if (MARKERS.none { it in text.lowercase() }) return null

        return when (kindOf(text)) {
            Kind.OUT_OF_CREDIT ->
                "Your AI provider says the account is out of credit, so I had to stop mid-task. " +
                    "Trying again won't help until you top it up or switch providers in Settings."

            Kind.PER_DAY ->
                "You've used up today's free quota for this model, so I had to stop mid-task. " +
                    "It resets tomorrow — or you can pick a different model in Settings."

            Kind.PER_MINUTE -> {
                val seconds = retryDelayMs(text)?.let { it / 1000 }
                val whenAgain = seconds?.let { " It should work again in about $it seconds." }
                    ?: " Give it a minute and try again."
                "I've hit the usage limit on your AI provider, so I had to stop mid-task.$whenAgain"
            }
        }
    }
}
