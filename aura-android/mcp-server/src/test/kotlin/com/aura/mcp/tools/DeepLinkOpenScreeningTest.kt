package com.aura.mcp.tools

import com.aura.mcp.bridge.UriResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GP2 — a custom-scheme deep link can resolve to a payment/banking APP even when
 * its raw URI (scheme/host) looks benign. The server already learns the true
 * target via `resolveUri`; [DeepLinkOpenScreening] screens that RESOLVED package
 * before `open_deeplink` fires.
 */
class DeepLinkOpenScreeningTest {

    private fun resolution(pkg: String?) =
        UriResolution(resolves = pkg != null, packageName = pkg, schemeAllowed = true, reason = null)

    @Test
    fun `resolving to an exact-blocked payment app is blocked`() {
        val out = DeepLinkOpenScreening.screen(resolution("com.paypal.android.p2pmobile"))
        assertTrue(out is DeepLinkOpenScreening.Outcome.Blocked, "expected Blocked, got $out")
    }

    @Test
    fun `resolving to a banking-pattern package is blocked`() {
        val out = DeepLinkOpenScreening.screen(resolution("com.mybank.mobile"))
        assertTrue(out is DeepLinkOpenScreening.Outcome.Blocked, "expected Blocked, got $out")
    }

    @Test
    fun `resolving to a benign app proceeds`() {
        assertEquals(
            DeepLinkOpenScreening.Outcome.Proceed,
            DeepLinkOpenScreening.screen(resolution("com.spotify.music")),
        )
    }

    @Test
    fun `unknown or blank target proceeds`() {
        assertEquals(DeepLinkOpenScreening.Outcome.Proceed, DeepLinkOpenScreening.screen(resolution(null)))
        assertEquals(DeepLinkOpenScreening.Outcome.Proceed, DeepLinkOpenScreening.screen(resolution("  ")))
        assertEquals(DeepLinkOpenScreening.Outcome.Proceed, DeepLinkOpenScreening.screen(null))
    }
}
