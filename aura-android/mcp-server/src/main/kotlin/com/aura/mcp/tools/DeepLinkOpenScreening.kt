package com.aura.mcp.tools

import com.aura.mcp.bridge.UriResolution
import com.aura.mcp.server.SensitivePolicy

/**
 * GP2 — resolved-target screening for `open_deeplink`.
 *
 * The pre-dispatch [SensitivePolicy] gate screens the raw URI (scheme, host) and
 * the optional explicit `package_name`, but a benign-looking custom-scheme link
 * can still **resolve** to a payment/banking app — only the device knows the
 * real target. The server already computes it (`resolveUri` /
 * [UriResolution.packageName]); this screens that resolved package before the
 * intent fires, closing the "open_deeplink → sensitive app" side door.
 *
 * Pure so it unit-tests without a device bridge.
 */
internal object DeepLinkOpenScreening {

    sealed interface Outcome {
        data object Proceed : Outcome
        data class Blocked(val category: String, val message: String) : Outcome
    }

    fun screen(resolution: UriResolution?): Outcome {
        val target = resolution?.packageName?.takeIf { it.isNotBlank() } ?: return Outcome.Proceed
        // screenPackage, not screen: the value is definitionally a package id, so
        // the freeform text rules must not apply (a package containing "ssn"
        // would false-positive the sensitive-text regexes).
        return when (val decision = SensitivePolicy.screenPackage(target)) {
            is SensitivePolicy.Decision.Block -> Outcome.Blocked(decision.category.id, decision.message)
            SensitivePolicy.Decision.Allow -> Outcome.Proceed
        }
    }
}
