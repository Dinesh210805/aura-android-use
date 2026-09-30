package com.aura.aura_ui.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2 of the 5 sessions captured on 2026-08-05 died here, and the user was told nothing — the
 * run simply ended "failed" and the phone sat there.
 *
 * Backoff is NOT the gap: `OpenAiCompatProvider` already wraps every client in Koog's
 * `RetryingLLMClient` (6 attempts, exponential to 60s, honours `Retry-After`), and the six
 * repeated error bodies in each failed trace are those attempts working as designed. The gap
 * is that once they are spent, nobody says why. The 429 body states the cause and the reset
 * delay in plain text; there is no reason to keep that from the person waiting.
 */
class QuotaExhaustionTest {

    private val geminiBody = """
        {"error":{"code":429,"message":"You exceeded your current quota, please check your
        plan and billing details. * Quota exceeded for metric:
        generativelanguage.googleapis.com/generate_content_free_tier_input_token_count,
        limit: 250000, model: gemini-3.5-flash-lite Please retry in 41.235709765s.",
        "status":"RESOURCE_EXHAUSTED"}}
    """.trimIndent()

    @Test
    fun `recognises a provider quota exhaustion`() {
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(geminiBody))
        assertTrue("exhaustion went unrecognised", reason != null)
    }

    @Test
    fun `says how long until it can try again`() {
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(geminiBody))!!
        assertTrue("the reset time was dropped: $reason", reason.contains("41"))
    }

    @Test
    fun `speaks plainly, without status codes or metric names`() {
        // This string is spoken aloud. "RESOURCE_EXHAUSTED for metric
        // generativelanguage.googleapis.com/generate_content_free_tier_input_token_count"
        // is not something a person should have to listen to.
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(geminiBody))!!
        assertTrue(!reason.contains("429"))
        assertTrue(!reason.contains("RESOURCE_EXHAUSTED"))
        assertTrue(!reason.contains("generativelanguage"))
    }

    @Test
    fun `finds it through a wrapped cause chain`() {
        // Koog wraps transport failures, so the 429 body is rarely the top-level message.
        val wrapped = IllegalStateException("agent failed", RuntimeException(geminiBody))
        assertTrue(QuotaExhaustion.spokenReasonFor(wrapped) != null)
    }

    @Test
    fun `ignores unrelated failures`() {
        assertNull(QuotaExhaustion.spokenReasonFor(RuntimeException("socket closed")))
        assertNull(QuotaExhaustion.spokenReasonFor(null))
    }

    // ── 2026-09-22: a 429 means three different things ────────────────────────

    /**
     * Gemini's per-day exhaustion. Telling this user to "give it a minute" sends them into a
     * try-fail-try-fail loop for the rest of the day — the quota id says `PerDay` outright.
     */
    private val perDayBody = """
        {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
        "message":"You exceeded your current quota.",
        "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{
        "quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
        "quotaId":"GenerateRequestsPerDayPerProjectPerModel","quotaValue":"500"}]}]}}
    """.trimIndent()

    @Test
    fun `a per-day quota says it resets tomorrow, not in a minute`() {
        assertEquals(QuotaExhaustion.Kind.PER_DAY, QuotaExhaustion.kindOf(perDayBody))
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(perDayBody))!!
        assertTrue(reason, reason.contains("tomorrow"))
        assertTrue(reason, !reason.contains("Give it a minute"))
    }

    @Test
    fun `a per-minute quota still quotes the stated wait`() {
        assertEquals(QuotaExhaustion.Kind.PER_MINUTE, QuotaExhaustion.kindOf(geminiBody))
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(geminiBody))!!
        assertTrue(reason, !reason.contains("tomorrow"))
    }

    /**
     * OpenAI's billing exhaustion shares the 429 AND the phrase "exceeded your current quota" with
     * rate limiting. Reading it as per-day would promise a reset that never comes: no delay fixes
     * an empty account. Checked before the per-day rule for exactly that reason.
     */
    @Test
    fun `out of credit says retrying will not help`() {
        val body = """{"error":{"message":"You exceeded your current quota, please check your plan
            and billing details.","type":"insufficient_quota","code":"insufficient_quota"}}"""
        assertEquals(QuotaExhaustion.Kind.OUT_OF_CREDIT, QuotaExhaustion.kindOf(body))
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(body))!!
        assertTrue(reason, reason.contains("out of credit"))
        assertTrue(reason, !reason.contains("tomorrow"))
    }

    /**
     * The trap this classifier must not fall into. Gemini's ordinary per-minute token-rate 429
     * contains "please check your plan and billing details" verbatim — the same sentence OpenAI
     * uses for an empty account. Keying out-of-credit on that prose would tell a user whose window
     * reopens in 41 seconds to go top up their card.
     */
    @Test
    fun `gemini's billing wording on a rate limit is not out of credit`() {
        assertTrue(geminiBody.contains("check your"))
        assertEquals(QuotaExhaustion.Kind.PER_MINUTE, QuotaExhaustion.kindOf(geminiBody))
    }

    /**
     * Gemini can list several violations in one body, so a "PerDay" quota id may ride along with
     * the per-minute one that actually tripped. A short stated wait settles it: of the two possible
     * mistakes, sending someone away until tomorrow is much the worse.
     */
    @Test
    fun `a per-day name with a short stated wait is still transient`() {
        val mixed = perDayBody.replace(
            """"quotaValue":"500"}]}]}}""",
            """"quotaValue":"500"}]},{"@type":"google.rpc.RetryInfo","retryDelay":"37s"}]}}""",
        )
        assertEquals(QuotaExhaustion.Kind.PER_MINUTE, QuotaExhaustion.kindOf(mixed))
    }

    /**
     * Captured from the device 2026-09-22, three times inside one agent run. Not a quota refusal —
     * the model itself was overloaded — but it fails the run exactly like one, and before this it
     * returned null here, so the agent ended with the generic "couldn't reach your AI provider".
     * That sent a whole evening's debugging at a git diff that had nothing to do with it.
     */
    private val overloadedBody = """
        [{"error":{"code":503,"message":"This model is currently experiencing high demand.
        Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}]
    """.trimIndent()

    @Test
    fun `recognises an overloaded model`() {
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(overloadedBody))
        assertTrue("a 503 UNAVAILABLE went unrecognised", reason != null)
    }

    @Test
    fun `an overloaded model is not reported as a quota or billing problem`() {
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(overloadedBody))!!
        val low = reason.lowercase()
        assertTrue("must not blame quota: $reason", "quota" !in low)
        assertTrue("must not blame billing/credit: $reason", "credit" !in low)
        assertTrue("should say the model is busy: $reason", "busy" in low || "demand" in low)
    }

    @Test
    fun `suggests switching model rather than waiting for a reset`() {
        val reason = QuotaExhaustion.spokenReasonFor(RuntimeException(overloadedBody))!!
        assertTrue("should point at another model: $reason", reason.lowercase().contains("model"))
    }
}
