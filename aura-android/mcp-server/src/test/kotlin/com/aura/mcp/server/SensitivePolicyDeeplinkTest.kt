package com.aura.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GP2 — the financial hard-block was bypassable through custom-scheme deep links,
 * the exact express-lane the agent is steered to prefer. `upi://pay?...` has host
 * "pay" (no banking-domain match) so it evaluated Allow and opened a live payment
 * sheet. These tests pin the payment-scheme blocklist in `evalUriTarget`.
 */
class SensitivePolicyDeeplinkTest {

    private fun openDeeplink(uri: String): SensitivePolicy.Decision =
        SensitivePolicy.evaluate("open_deeplink", JsonObject(mapOf("uri" to JsonPrimitive(uri))))

    private fun assertBlocked(uri: String) {
        val d = openDeeplink(uri)
        assertTrue(d is SensitivePolicy.Decision.Block, "expected $uri to be BLOCKED, got $d")
    }

    @Test
    fun `UPI payment scheme is blocked`() {
        assertBlocked("upi://pay?pa=merchant@okhdfcbank&pn=Merchant&am=500&cu=INR")
    }

    @Test
    fun `other payment schemes are blocked`() {
        assertBlocked("tez://upi/pay?pa=x&am=100")           // Google Pay India (legacy)
        assertBlocked("phonepe://pay?pa=x")
        assertBlocked("paytmmp://pay?pa=x")
        assertBlocked("bhim://pay?pa=x")
        assertBlocked("venmo://paycharge?txn=pay&amount=20")
    }

    @Test
    fun `payment scheme block survives an unparseable query`() {
        // java.net.URI may choke on some payment URIs — the scheme extraction
        // must still fire the block via the raw-scheme fallback.
        assertBlocked("upi://pay?pa=merchant name@bank&am=₹500")
    }

    @Test
    fun `benign web and app deep links are allowed`() {
        assertEquals(SensitivePolicy.Decision.Allow, openDeeplink("https://open.spotify.com/search/jazz"))
        assertEquals(SensitivePolicy.Decision.Allow, openDeeplink("spotify://playlist/37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun `banking web host still blocked (regression guard)`() {
        assertBlocked("https://www.chase.com/login")
    }

    @Test
    fun `app-shortcut to a payment app still blocked (regression guard)`() {
        assertBlocked("app-shortcut://com.paypal.android.p2pmobile/send")
    }

    // E3 — open_deeplink accepts `app_name`; the raw name is screened pre-dispatch
    // exactly like launch_app (the handler re-screens the RESOLVED package).

    @Test
    fun `open_deeplink with a banking app_name blocks pre-dispatch`() {
        val d = SensitivePolicy.evaluate(
            "open_deeplink",
            JsonObject(
                mapOf(
                    "uri" to JsonPrimitive("https://example.com/x"),
                    "app_name" to JsonPrimitive("PayPal"),
                ),
            ),
        )
        assertTrue(d is SensitivePolicy.Decision.Block, "expected app_name=PayPal to be BLOCKED, got $d")
    }

    @Test
    fun `open_deeplink with an ordinary app_name allows`() {
        val d = SensitivePolicy.evaluate(
            "open_deeplink",
            JsonObject(
                mapOf(
                    "uri" to JsonPrimitive("https://example.com/x"),
                    "app_name" to JsonPrimitive("Spotify"),
                ),
            ),
        )
        assertEquals(SensitivePolicy.Decision.Allow, d)
    }
}
