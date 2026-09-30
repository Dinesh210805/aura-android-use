package com.aura.aura_ui.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsBucketsTest {

    @Test fun `duration bands`() {
        assertEquals("<10s", AnalyticsBuckets.durationBand(-5))
        assertEquals("<10s", AnalyticsBuckets.durationBand(9_999))
        assertEquals("10-30s", AnalyticsBuckets.durationBand(10_000))
        assertEquals("30-60s", AnalyticsBuckets.durationBand(59_999))
        assertEquals("1-3m", AnalyticsBuckets.durationBand(60_000))
        assertEquals("3-10m", AnalyticsBuckets.durationBand(180_000))
        assertEquals(">10m", AnalyticsBuckets.durationBand(600_000))
    }

    @Test fun `error categories`() {
        assertEquals("no_key", AnalyticsBuckets.errorCategory("java.lang.IllegalStateException", "No API key configured for Groq — open Settings → Brain."))
        assertEquals("auth", AnalyticsBuckets.errorCategory("x.HttpException", "HTTP 401 Unauthorized"))
        assertEquals("auth", AnalyticsBuckets.errorCategory("x.HttpException", "Invalid API key provided"))
        assertEquals("rate_limit", AnalyticsBuckets.errorCategory("x.HttpException", "HTTP 429: Too Many Requests"))
        assertEquals("rate_limit", AnalyticsBuckets.errorCategory("x.HttpException", "You exceeded your current quota"))
        assertEquals("timeout", AnalyticsBuckets.errorCategory("java.net.SocketTimeoutException", "Read timed out"))
        assertEquals("network", AnalyticsBuckets.errorCategory("java.net.UnknownHostException", "Unable to resolve host"))
        assertEquals("other", AnalyticsBuckets.errorCategory("java.lang.RuntimeException", "boom"))
        assertEquals("other", AnalyticsBuckets.errorCategory("java.lang.RuntimeException", null))
    }

    /** "4010 tokens" must not read as HTTP 401. */
    @Test fun `status codes match whole numbers only`() =
        assertEquals("other", AnalyticsBuckets.errorCategory("x.E", "context 4010 tokens over"))

    @Test fun `custom providers are never named`() {
        assertEquals("gemini", AnalyticsBuckets.providerFamily("gemini", isBuiltin = true))
        assertEquals("custom", AnalyticsBuckets.providerFamily("my-home-server.example:8080", isBuiltin = false))
    }

    @Test fun `pc os`() {
        assertEquals("windows", AnalyticsBuckets.pcOs("win32"))
        assertEquals("mac", AnalyticsBuckets.pcOs("darwin"))
        assertEquals("linux", AnalyticsBuckets.pcOs("linux"))
        assertEquals("other", AnalyticsBuckets.pcOs(null))
        assertEquals("other", AnalyticsBuckets.pcOs("freebsd"))
    }
}
