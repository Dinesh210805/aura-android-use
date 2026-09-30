package com.aura.mcp.tools

import com.aura.mcp.bridge.AppCandidate
import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.server.SensitivePolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E3 — dispatch-time name resolution for launch_app: `app_name` resolves
 * in-process via the existing lookup bridge (no lookup_app LLM round trip);
 * ambiguity returns ranked candidates as the error result; and the RESOLVED
 * package is re-screened through SensitivePolicy (the pre-dispatch gate only
 * saw the human-readable name — pairs with GP2).
 */
class LaunchAppResolutionTest {

    private fun lookupReturning(result: AppLookupResult): (String) -> AppLookupResult = { result }

    private val neverCalled: (String) -> AppLookupResult = {
        throw AssertionError("lookup must not be called when package_name is provided")
    }

    @Test
    fun `explicit package_name passes through without lookup`() {
        val out = LaunchAppResolution.resolve("com.spotify.music", null, neverCalled)
        assertEquals(LaunchAppResolution.Outcome.Launch("com.spotify.music", null), out)
    }

    @Test
    fun `package_name wins when both arguments are supplied`() {
        val out = LaunchAppResolution.resolve("com.spotify.music", "Spotify", neverCalled)
        assertEquals(LaunchAppResolution.Outcome.Launch("com.spotify.music", null), out)
    }

    @Test
    fun `app_name resolves to the found package`() {
        val out = LaunchAppResolution.resolve(
            null, "Amazon",
            lookupReturning(
                AppLookupResult(
                    found = true,
                    packageName = "in.amazon.mShop.android.shopping",
                    appName = "Amazon",
                    candidates = emptyList(),
                    error = null,
                ),
            ),
        )
        assertEquals(
            LaunchAppResolution.Outcome.Launch("in.amazon.mShop.android.shopping", "Amazon"),
            out,
        )
    }

    @Test
    fun `resolved package is screened - PayPal by name is blocked`() {
        val out = LaunchAppResolution.resolve(
            null, "PayPal",
            lookupReturning(
                AppLookupResult(
                    found = true,
                    packageName = "com.paypal.android.p2pmobile",
                    appName = "PayPal",
                    candidates = emptyList(),
                    error = null,
                ),
            ),
        )
        assertTrue(out is LaunchAppResolution.Outcome.Blocked, "resolved financial package must be blocked")
        assertEquals(SensitivePolicy.Category.FINANCIAL_APP.id, out.category)
    }

    @Test
    fun `ambiguous name returns the ranked candidates`() {
        val candidates = listOf(
            AppCandidate("com.google.android.gm", "Gmail"),
            AppCandidate("com.google.android.gms", "Google Play services"),
        )
        val out = LaunchAppResolution.resolve(
            null, "google",
            lookupReturning(
                AppLookupResult(found = false, packageName = "", appName = "", candidates = candidates, error = null),
            ),
        )
        assertTrue(out is LaunchAppResolution.Outcome.Ambiguous)
        assertEquals(candidates, out.candidates)
    }

    @Test
    fun `unknown name with no candidates is invalid`() {
        val out = LaunchAppResolution.resolve(
            null, "Nonexistent App",
            lookupReturning(
                AppLookupResult(found = false, packageName = "", appName = "", candidates = emptyList(), error = null),
            ),
        )
        assertTrue(out is LaunchAppResolution.Outcome.Invalid)
    }

    @Test
    fun `neither argument is invalid`() {
        val out = LaunchAppResolution.resolve(null, null, neverCalled)
        assertTrue(out is LaunchAppResolution.Outcome.Invalid)
    }
}
