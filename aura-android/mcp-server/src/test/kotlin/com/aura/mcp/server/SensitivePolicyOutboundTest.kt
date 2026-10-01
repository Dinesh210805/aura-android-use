package com.aura.mcp.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Every argument that carries text off the device is screened: URLs (a code in a query string
 * reaches the site's server with no UI at all), deep-link queries, search queries and share
 * subjects. URLs get the OTP and banking-host rules only — long numeric ids in paths would trip
 * the card-number rule.
 */
class SensitivePolicyOutboundTest {

    @BeforeTest fun seeCode() {
        OtpSighting.clear()
        OtpSighting.noteSeen("Your verification code is 482913. Do not share it.")
    }

    @AfterTest fun reset() = OtpSighting.clear()

    private fun eval(tool: String, vararg pairs: Pair<String, String>) =
        SensitivePolicy.evaluate(tool, buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })

    private fun assertOtpBlock(d: SensitivePolicy.Decision) {
        assertIs<SensitivePolicy.Decision.Block>(d)
        assertEquals(SensitivePolicy.Category.OTP_EXFILTRATION, d.category)
    }

    @Test
    fun `a sighted code in a browser URL is blocked`() {
        assertOtpBlock(eval("browser_open", "url" to "https://example.com/?c=482913"))
        assertOtpBlock(eval("browser_tabs", "action" to "open", "url" to "https://example.com/482913"))
    }

    @Test
    fun `a sighted code in a deep-link query is blocked`() {
        assertOtpBlock(eval("open_deeplink", "uri" to "smsto:5551234?body=482913"))
        assertOtpBlock(eval("open_deeplink", "uri" to "https://wa.me/15551234?text=code%20482913"))
    }

    @Test
    fun `search queries and share subjects get the text screen`() {
        assertOtpBlock(eval("web_search", "query" to "482913"))
        assertOtpBlock(eval("system_intent", "action" to "share_text", "text" to "hi", "subject" to "482913"))
        assertIs<SensitivePolicy.Decision.Block>(eval("web_search", "query" to "my password is hunter2"))
    }

    @Test
    fun `banking hosts are refused in the browser`() {
        assertIs<SensitivePolicy.Decision.Block>(eval("browser_open", "url" to "https://www.chase.com/"))
        assertIs<SensitivePolicy.Decision.Block>(eval("browser_tabs", "action" to "open", "url" to "https://paypal.com"))
    }

    @Test
    fun `ordinary URLs and long numeric ids still pass`() {
        assertIs<SensitivePolicy.Decision.Allow>(eval("browser_open", "url" to "https://example.com/news"))
        // 19-digit, Luhn-valid status id: the card rule must not apply to URLs.
        assertIs<SensitivePolicy.Decision.Allow>(
            eval("browser_open", "url" to "https://x.com/someone/status/4111111111111111111"),
        )
        assertIs<SensitivePolicy.Decision.Allow>(eval("web_search", "query" to "weather in chennai"))
        assertIs<SensitivePolicy.Decision.Allow>(eval("browser_tabs", "action" to "list"))
    }
}
