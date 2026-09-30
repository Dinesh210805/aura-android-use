package com.aura.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Static app shortcuts fire through `open_deeplink` as synthetic
 * `app-shortcut://<package>/<shortcut-id>` URIs. The URI *host* is therefore a
 * package name, so the pre-dispatch gate must screen it with the PACKAGE rules
 * (exact financial/auth blocklists), not just the banking-host substrings —
 * otherwise `app-shortcut://com.venmo/pay` slips past evalHost (which only
 * substring-matches domains) and GP2 repeats through the shortcut lane.
 */
class SensitivePolicyShortcutUriTest {

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `app-shortcut uri to blocked financial package is blocked`() {
        val decision = SensitivePolicy.evaluate(
            "open_deeplink",
            args("uri" to "app-shortcut://com.google.android.apps.walletnfcrel/pay"),
        )
        assertTrue(decision is SensitivePolicy.Decision.Block, "Google Wallet shortcut must be blocked")
    }

    @Test
    fun `app-shortcut uri to blocked auth package is blocked`() {
        val decision = SensitivePolicy.evaluate(
            "open_deeplink",
            args("uri" to "app-shortcut://com.azure.authenticator/scan"),
        )
        assertTrue(decision is SensitivePolicy.Decision.Block)
    }

    @Test
    fun `app-shortcut uri to banking-pattern package is blocked`() {
        val decision = SensitivePolicy.evaluate(
            "open_deeplink",
            args("uri" to "app-shortcut://net.one97.paytm/scan_qr"),
        )
        assertTrue(decision is SensitivePolicy.Decision.Block)
    }

    @Test
    fun `app-shortcut uri to harmless package is allowed`() {
        val decision = SensitivePolicy.evaluate(
            "open_deeplink",
            args("uri" to "app-shortcut://com.whatsapp/new_chat"),
        )
        assertTrue(decision is SensitivePolicy.Decision.Allow)
    }
}
