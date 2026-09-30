package com.aura.mcp.tools

import com.aura.mcp.bridge.AppCandidate
import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.server.SensitivePolicy
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Red-team 2026-07-18: `validate_action` was name-blind — `SensitivePolicy.screen("Google Pay")`
 * returns Allow because a human app NAME matches no package/host/text pattern, so the advisory
 * verdict disagreed with the enforcing `launch_app` gate (which blocks the RESOLVED package).
 * These pin the fix: for app-launch gestures, the name is resolved and the resolved package screened.
 */
class ValidateActionNameResolveTest {

    /** Fake installed-app table: names the real device exposed during the red-team. */
    private val installed = mapOf(
        "google pay" to "com.google.android.apps.nbu.paisa.user",
        "microsoft authenticator" to "com.azure.authenticator",
        "authenticator" to "com.azure.authenticator",
        "spotify" to "com.spotify.music",
    )

    private val lookup: (String) -> AppLookupResult = { q ->
        val pkg = installed[q.trim().lowercase()]
        if (pkg != null) {
            AppLookupResult(true, pkg, q, listOf(AppCandidate(pkg, q)), null)
        } else {
            AppLookupResult(false, "", q, emptyList(), "No installed app matches '$q'")
        }
    }

    private fun screen(gesture: String, target: String) =
        resolveAndScreenAppName(gesture, target, lookup)

    @Test fun `human name Google Pay resolves and blocks for launch_app`() {
        assertTrue(screen("launch_app", "Google Pay") is SensitivePolicy.Decision.Block)
    }

    @Test fun `human name Microsoft Authenticator resolves and blocks`() {
        assertTrue(screen("launch_app", "Microsoft Authenticator") is SensitivePolicy.Decision.Block)
        assertTrue(screen("open_deeplink", "Authenticator") is SensitivePolicy.Decision.Block)
    }

    @Test fun `benign app name stays allowed (no false positive)`() {
        assertTrue(screen("launch_app", "Spotify") is SensitivePolicy.Decision.Allow)
    }

    @Test fun `non-app gestures are not name-resolved (type_text target is literal text)`() {
        // "Google Pay" as text-to-type must NOT be treated as an app name.
        assertTrue(screen("type_text", "Google Pay") is SensitivePolicy.Decision.Allow)
    }

    @Test fun `lookup miss fails open (enforcing gate is the real boundary)`() {
        assertTrue(screen("launch_app", "Some Unknown App") is SensitivePolicy.Decision.Allow)
    }

    @Test fun `absent lookup bridge fails open`() {
        assertTrue(resolveAndScreenAppName("launch_app", "Google Pay", null) is SensitivePolicy.Decision.Allow)
    }
}
