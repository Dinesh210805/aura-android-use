package com.aura.aura_ui.data.deeplink

import com.aura.mcp.bridge.DeepLinkEntry

/**
 * One static app shortcut parsed from a target app's `shortcuts.xml`
 * (`android.app.shortcuts` meta-data resource). Produced by
 * [StaticShortcutReader]; consumed by [ShortcutEntrySynthesizer].
 *
 * Only **static** shortcuts are reachable this way — dynamic/pinned shortcuts
 * need `LauncherApps` + default-launcher status, which AURA doesn't have.
 */
data class StaticShortcutInfo(
    val id: String,
    val label: String?,
    val targetPackage: String?,
    val action: String?,
    val dataUri: String?,
    val targetClass: String?,
    val enabled: Boolean,
)

/**
 * Turns static shortcuts into `source = "shortcut"` deep-link entries with a
 * synthetic `app-shortcut://<package>/<shortcut-id>` URI.
 *
 * Shortcuts are the app developer's own curated top entry points ("New chat",
 * "Take selfie") — higher trust than anything we synthesize from raw intent
 * filters. The synthetic URI rides the existing `open_deeplink` tool: the
 * bridge intercepts the `app-shortcut` scheme, re-reads the target app's own
 * shortcuts.xml, and fires exactly the intent the app authored. The agent
 * supplies only identifiers, never intent contents.
 *
 * Safety filters:
 *  - disabled shortcuts are skipped
 *  - a shortcut whose intent targets a *different* package is skipped
 *    (nothing legitimate does this; honoring it would let one app launder an
 *    intent into another)
 *  - no action → not fireable → skipped
 */
object ShortcutEntrySynthesizer {

    const val SCHEME = "app-shortcut"

    fun synthesize(packageName: String, shortcuts: List<StaticShortcutInfo>): List<DeepLinkEntry> {
        val byId = LinkedHashMap<String, DeepLinkEntry>()

        for (shortcut in shortcuts) {
            if (!shortcut.enabled) continue
            if (shortcut.action.isNullOrBlank()) continue
            if (shortcut.targetPackage != null && shortcut.targetPackage != packageName) continue
            if (byId.containsKey(shortcut.id)) continue

            byId[shortcut.id] = DeepLinkEntry(
                exampleUri = "$SCHEME://$packageName/${shortcut.id}",
                uriTemplate = null,
                scheme = SCHEME,
                host = packageName,
                label = shortcut.label?.takeIf { it.isNotBlank() } ?: shortcut.id,
                source = "shortcut",
                requiresConfirmation = false,
                shortcutId = shortcut.id,
            )
        }

        return byId.values.toList()
    }
}
