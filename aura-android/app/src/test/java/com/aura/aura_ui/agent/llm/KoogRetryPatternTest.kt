package com.aura.aura_ui.agent.llm

import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryablePattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation test for the retry policy we do NOT own.
 *
 * `RetryingLLMClient` decides what to retry by matching [RetryablePattern]s against the exception
 * **message string** — there is no structured status code involved. The design doc for this station
 * asserted that a 400 (e.g. "this model does not accept image input") rides the same ladder as a
 * 429 and therefore sleeps for a minute before failing. That claim was never measured. These tests
 * measure it, so the assumption is checked rather than believed, and so a Koog upgrade that widens
 * the default patterns fails here instead of silently costing every doomed run a minute.
 */
class KoogRetryPatternTest {

    private fun retryable(message: String): Boolean =
        RetryConfig.DEFAULT_PATTERNS.any { it.matches(message) }

    /** The realistic shape of the failure P1 describes: a hard schema/capability rejection. */
    @Test fun `a 400 for an unsupported image part is NOT retried`() {
        assertFalse(
            retryable(
                "Error from provider: 400 Bad Request " +
                    "{\"error\":{\"message\":\"Invalid content type. This model does not support " +
                    "image input.\",\"type\":\"invalid_request_error\",\"code\":400}}",
            ),
        )
    }

    @Test fun `other hard client errors are NOT retried`() {
        assertFalse(retryable("401 Unauthorized: invalid api key"))
        assertFalse(retryable("404 Not Found: model does not exist"))
        assertFalse(retryable("400 Bad Request: tool_use_failed"))
    }

    /** The genuine rate-limit case still must be retried — that is the whole point of the ladder. */
    @Test fun `rate limiting is retried`() {
        assertTrue(retryable("429 Too Many Requests"))
        assertTrue(retryable("Request failed: rate limit reached for model, retry in 12s"))
    }

    @Test fun `transient server and transport failures are retried`() {
        assertTrue(retryable("503 Service Unavailable"))
        assertTrue(retryable("500 Internal Server Error"))
        assertTrue(retryable("java.net.SocketTimeoutException: read timeout"))
    }

    /**
     * The sharp edge that survives: matching is substring-based over the whole message, so a hard
     * error whose body happens to quote a retryable phrase is retried anyway. Recorded as a known
     * property rather than left to be rediscovered as a mystery multi-minute stall.
     */
    @Test fun `a hard error quoting a retryable phrase is retried anyway - known sharp edge`() {
        assertTrue(retryable("400 Bad Request: your prompt mentioned the rate limit policy"))
    }

    @Test fun `the status list we rely on is the one Koog actually ships`() {
        val statuses: Set<Int> = RetryConfig.DEFAULT_PATTERNS
            .filterIsInstance<RetryablePattern.Status>()
            .map { it.code }
            .toSet()
        assertTrue("429 must be retryable", 429 in statuses)
        assertFalse("400 must never be in the retryable status list", 400 in statuses)
        assertFalse("401 must never be in the retryable status list", 401 in statuses)
    }
}
