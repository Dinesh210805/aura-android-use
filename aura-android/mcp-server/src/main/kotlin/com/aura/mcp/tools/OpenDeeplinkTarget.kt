package com.aura.mcp.tools

import com.aura.mcp.bridge.AppLookupResult

/**
 * E3 — dispatch-time pin-target resolution for `open_deeplink`, mirroring
 * `launch_app`: an optional `app_name` resolves via the lookup bridge and the
 * RESOLVED package is screened (inside [LaunchAppResolution.resolve]) before
 * the intent is pinned to it. Both args absent = unpinned open (null).
 */
internal object OpenDeeplinkTarget {

    fun resolve(
        packageName: String?,
        appName: String?,
        lookup: (String) -> AppLookupResult,
    ): LaunchAppResolution.Outcome? {
        if (packageName.isNullOrBlank() && appName.isNullOrBlank()) return null
        return LaunchAppResolution.resolve(packageName, appName, lookup)
    }
}
