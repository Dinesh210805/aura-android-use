package com.aura.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GP7 — the financial blocklists were US-centric and stale for this device's market. Google Pay
 * India (`…nbu.paisa.user` — "paisa" matches no pattern), BHIM, Amazon Pay, CRED, Slice all passed.
 * These pin the major UPI / India-market packages as hard-blocked.
 */
class SensitivePolicyMarketListTest {

    private fun launch(pkg: String) =
        SensitivePolicy.evaluate("launch_app", JsonObject(mapOf("package_name" to JsonPrimitive(pkg))))

    private fun assertBlocked(pkg: String) {
        assertTrue(launch(pkg) is SensitivePolicy.Decision.Block, "expected $pkg blocked, got ${launch(pkg)}")
    }

    @Test fun `google pay india is blocked`() = assertBlocked("com.google.android.apps.nbu.paisa.user")

    @Test fun `major india market payment apps are blocked`() {
        assertBlocked("in.org.npci.upiapp")       // BHIM
        assertBlocked("com.dreamplug.androidapp")  // CRED
        assertBlocked("in.slice.android")          // Slice
        assertBlocked("com.mobikwik_new")          // MobiKwik
        assertBlocked("com.phonepe.app")           // PhonePe (pattern)
        assertBlocked("net.one97.paytm")           // Paytm (pattern)
    }

    @Test fun `existing US apps still blocked (regression)`() {
        assertBlocked("com.paypal.android.p2pmobile")
        assertBlocked("com.venmo")
    }

    @Test fun `general shopping and non-payment apps are NOT blocked (false-positive guard)`() {
        // Amazon shopping / ride apps are legitimate automation targets — only dedicated
        // payment/financial apps are gated.
        assertTrue(launch("in.amazon.mShop.android.shopping") is SensitivePolicy.Decision.Allow)
        assertTrue(launch("com.spotify.music") is SensitivePolicy.Decision.Allow)
    }
}
