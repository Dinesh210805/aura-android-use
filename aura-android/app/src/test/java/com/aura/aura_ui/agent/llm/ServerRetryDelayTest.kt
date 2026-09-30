package com.aura.aura_ui.agent.llm

import com.aura.aura_ui.agent.strategy.AgentContextConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4 (2026-09-14): Gemini's 429 says "Please retry in 30.8s"; the agent retried after 1.8/2.1/3.3/5.4 s
 * and died. Koog's default extractor cannot read that wording — these pin that we now can.
 */
class ServerRetryDelayTest {

    private val gemini429 =
        "Error from client: OpenAI\nStatus code: 429\nError body: [{\"error\":{\"code\":429," +
            "\"message\":\"You exceeded your current quota. Quota exceeded for metric: " +
            "generate_content_free_tier_input_token_count, limit: 250000. Please retry in 30.812s.\"," +
            "\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"retryDelay\":\"30s\"}]}}]"

    @Test fun `koog's default extractor cannot read gemini's wording - the bug`() {
        assertNull(ai.koog.prompt.executor.clients.retry.DefaultRetryAfterExtractor.extract(gemini429))
    }

    @Test fun `the policy wires our extractor in`() {
        assertTrue(AgentRetryPolicy.configFor(AgentContextConfig.DEFAULT).retryAfterExtractor is ServerRetryDelay)
    }

    @Test fun `waits the server's delay plus a margin`() {
        assertEquals(31_812.milliseconds, ServerRetryDelay(90_000).extract(gemini429))
    }

    @Test fun `reads the structured retryDelay field when the sentence is absent`() {
        assertEquals(12_000L, QuotaExhaustion.retryDelayMs("""{"retryDelay": "12s"}"""))
    }

    @Test fun `a wait that would exceed the ceiling fails fast instead of sleeping`() {
        val delay = ServerRetryDelay(ceilingMs = 70_000)
        assertEquals(31_812.milliseconds, delay.extract(gemini429))
        assertEquals(31_812.milliseconds, delay.extract(gemini429)) // 63.6 s total, fits
        assertEquals(Duration.ZERO, delay.extract(gemini429)) // 95.4 s would not
    }

    @Test fun `messages without a server wait fall back to koog's default`() {
        val delay = ServerRetryDelay(90_000)
        assertNull(delay.extract("503 Service Unavailable"))
        assertEquals(7_000.milliseconds, delay.extract("Retry-After: 7"))
    }
}
