package com.aura.mcp.tools

import com.aura.mcp.bridge.AppCandidate
import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.server.SensitivePolicy

/**
 * E3 — dispatch-time name resolution for `launch_app`: the model may pass a
 * human-readable `app_name` and the server resolves it in-process via the
 * existing lookup bridge, killing the `lookup_app` → `launch_app` LLM round
 * trip. Ambiguity comes back as a ranked-candidate error the model can answer
 * in its next (single) call.
 *
 * Safety (GP2 pairing): the pre-dispatch [SensitivePolicy] gate only saw the
 * raw arguments — when we resolve a NAME to a package here, the **resolved**
 * package must be screened again before launching, or "launch PayPal by name"
 * would bypass the financial hard block.
 */
internal object LaunchAppResolution {

    sealed interface Outcome {
        /** Ready to launch. [resolvedFromAppName] is set when a name lookup produced the package. */
        data class Launch(val packageName: String, val resolvedFromAppName: String?) : Outcome
        data class Blocked(val category: String, val message: String) : Outcome
        data class Ambiguous(val query: String, val candidates: List<AppCandidate>) : Outcome
        data class Invalid(val message: String) : Outcome
    }

    fun resolve(
        packageName: String?,
        appName: String?,
        lookup: (String) -> AppLookupResult,
    ): Outcome {
        // Explicit package: already screened pre-dispatch by SensitivePolicy.
        if (!packageName.isNullOrBlank()) return Outcome.Launch(packageName, null)
        if (appName.isNullOrBlank()) {
            return Outcome.Invalid("launch_app requires 'package_name' or 'app_name'")
        }

        val result = lookup(appName)
        if (!result.found || result.packageName.isBlank()) {
            return if (result.candidates.isNotEmpty()) {
                Outcome.Ambiguous(appName, result.candidates)
            } else {
                Outcome.Invalid(
                    "No installed app matches '$appName'." +
                        (result.error?.let { " ($it)" } ?: ""),
                )
            }
        }

        // Screen the RESOLVED package — the policy gate never saw it. screenPackage,
        // not screen: a package id must not be run through the freeform text rules.
        return when (val decision = SensitivePolicy.screenPackage(result.packageName)) {
            is SensitivePolicy.Decision.Block -> Outcome.Blocked(decision.category.id, decision.message)
            SensitivePolicy.Decision.Allow -> Outcome.Launch(result.packageName, result.appName)
        }
    }
}
