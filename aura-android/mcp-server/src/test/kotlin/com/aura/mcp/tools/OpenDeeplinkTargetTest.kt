package com.aura.mcp.tools

import com.aura.mcp.bridge.AppCandidate
import com.aura.mcp.bridge.AppLookupResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * E3 — open_deeplink accepts app_name; resolution + resolved-package screening
 * reuse LaunchAppResolution. Null outcome = nothing to pin (both args absent).
 */
class OpenDeeplinkTargetTest {

    private fun lookupReturning(result: AppLookupResult): (String) -> AppLookupResult = { result }

    private fun found(pkg: String, name: String) = AppLookupResult(
        found = true,
        packageName = pkg,
        appName = name,
        candidates = emptyList(),
        error = null,
    )

    @Test
    fun `no package and no name means unpinned`() {
        assertNull(OpenDeeplinkTarget.resolve(null, null) { error("must not look up") })
        assertNull(OpenDeeplinkTarget.resolve("", "  ") { error("must not look up") })
    }

    @Test
    fun `explicit package pins without lookup`() {
        val out = OpenDeeplinkTarget.resolve("com.spotify.music", null) { error("must not look up") }
        assertIs<LaunchAppResolution.Outcome.Launch>(out)
        assertEquals("com.spotify.music", out.packageName)
    }

    @Test
    fun `app_name resolves then pins`() {
        val out = OpenDeeplinkTarget.resolve(
            null,
            "Spotify",
            lookupReturning(found("com.spotify.music", "Spotify")),
        )
        assertIs<LaunchAppResolution.Outcome.Launch>(out)
        assertEquals("com.spotify.music", out.packageName)
        assertEquals("Spotify", out.resolvedFromAppName)
    }

    @Test
    fun `app_name resolving to a banking package is blocked`() {
        val out = OpenDeeplinkTarget.resolve(
            null,
            "My Bank",
            lookupReturning(found("com.mybank.mobile", "My Bank")),
        )
        assertIs<LaunchAppResolution.Outcome.Blocked>(out)
    }

    @Test
    fun `ambiguous name returns ranked candidates`() {
        val out = OpenDeeplinkTarget.resolve(
            null,
            "photo",
            lookupReturning(
                AppLookupResult(
                    found = false,
                    packageName = "",
                    appName = "",
                    candidates = listOf(
                        AppCandidate(packageName = "com.google.android.apps.photos", appName = "Photos"),
                        AppCandidate(packageName = "com.example.editor", appName = "Photo Editor"),
                    ),
                    error = null,
                ),
            ),
        )
        assertIs<LaunchAppResolution.Outcome.Ambiguous>(out)
        assertEquals(2, out.candidates.size)
    }
}
