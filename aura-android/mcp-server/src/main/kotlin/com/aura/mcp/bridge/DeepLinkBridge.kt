package com.aura.mcp.bridge

/**
 * Port (Hexagonal Architecture term) into the host app's deep-link facilities.
 *
 * Deep links are a **non-gesture** action surface: they drive the device by
 * firing an `Intent`, not by tapping pixels. That's deliberately kept separate
 * from [DeviceBridge] (gestures / accessibility) so each port stays single-
 * responsibility. The `:app` module binds this to a real implementation backed
 * by `PackageManager` + `DomainVerificationManager`; `:mcp-server` only declares
 * the shape it needs.
 *
 * ## Why discovery is awkward (and why this interface looks the way it does)
 *
 * Android offers no clean API to enumerate *all* of another app's deep-link
 * intent filters with concrete URIs. The data-test rule for intent resolution
 * means you can only match a filter if you can already construct a URI that
 * matches it. So the implementation layers three sources, weakest last, and
 * every returned [DeepLinkEntry] carries a [DeepLinkEntry.source] tag so the
 * agent can weight trust — the same pattern perception uses for ui_tree vs
 * omniparser:
 *
 *  - `verified`   — https host the app owns, per `DomainVerificationManager`
 *                   (API 31+). Highest trust.
 *  - `shortcut`   — the app's own static shortcut (`shortcuts.xml`), exposed as
 *                   a synthetic `app-shortcut://<pkg>/<id>` URI that [openUri]
 *                   resolves back to the app-authored intent.
 *  - `resolved`   — a curated-catalog template whose concrete form actually
 *                   resolves on *this* device (checked via `resolveActivity`).
 *  - `catalog`    — a curated template that did not resolve-probe (best guess).
 *  - `discovered` — VIEW intent-filters read from the app's binary manifest,
 *                   resolve-probed before listing; lowest trust.
 */
interface DeepLinkBridge {

    /**
     * Discover deep links for a single [packageName]. Queried live per call —
     * cheap for one package and always fresh, so no caching here.
     */
    suspend fun listDeepLinks(packageName: String): DeepLinkCatalog

    /**
     * Dry-run a concrete [uri]: does it resolve, and to which package? Fires
     * nothing. Also reports whether the scheme is on the open allowlist, so the
     * agent can tell "won't resolve" apart from "blocked for safety".
     */
    fun resolveUri(uri: String): UriResolution

    /**
     * Fire [uri] as an `ACTION_VIEW` intent. When [packageName] is non-null the
     * intent is pinned to that package (`setPackage`) to prevent scheme hijack.
     * Disallowed schemes are rejected before any intent is started.
     */
    fun openUri(uri: String, packageName: String?): OpenUriResult
}

/**
 * Deep links discovered for one app.
 *
 * @param packageName the app queried
 * @param appName human-readable label, best-effort
 * @param entries discovered links, most-trustworthy source first
 */
data class DeepLinkCatalog(
    val packageName: String,
    val appName: String,
    val entries: List<DeepLinkEntry>,
)

/**
 * One openable (or template) deep link.
 *
 * @param exampleUri a concrete URI the agent can pass straight to `open_deeplink`
 *   when the entry needs no parameters, or a filled example when it does
 * @param uriTemplate the parameterised form (e.g. `https://open.spotify.com/search/{query}`)
 *   when this entry has slots; null for fixed URIs
 * @param scheme the URI scheme (`https`, `spotify`, the `android.settings.*`
 *   marker, …) for quick filtering
 * @param host the authority for web links; null for scheme-only links
 * @param label short human description for the agent ("Search Spotify")
 * @param source provenance / trust tag — see [DeepLinkBridge]
 * @param requiresConfirmation true for links that could send / pay / delete /
 *   authenticate; the agent must stop and confirm before firing these
 * @param shortcutId set only for `source = "shortcut"` entries (static app
 *   shortcuts). Their [exampleUri] is a synthetic `app-shortcut://<pkg>/<id>`
 *   that `open_deeplink` resolves back to the app's own shortcut intent.
 */
data class DeepLinkEntry(
    val exampleUri: String,
    val uriTemplate: String?,
    val scheme: String?,
    val host: String?,
    val label: String,
    val source: String,
    val requiresConfirmation: Boolean,
    val shortcutId: String? = null,
)

/**
 * Outcome of a [DeepLinkBridge.resolveUri] dry-run.
 *
 * @param resolves whether any activity would handle the URI
 * @param packageName the resolved target package, when known
 * @param schemeAllowed whether the URI's scheme is on the open allowlist
 *   (a `false` here means `open_deeplink` will refuse it for safety)
 * @param reason short explanation when [resolves] is false or scheme blocked
 */
data class UriResolution(
    val resolves: Boolean,
    val packageName: String?,
    val schemeAllowed: Boolean,
    val reason: String?,
    // How the URI is handled on THIS device:
    //  "app"          — a specific non-browser app handles it (packageName set)
    //  "browser_only" — only a browser / the system chooser handles it (openable,
    //                   but it will not land inside a target app)
    //  "none"         — nothing handles it
    // Null only on the legacy/blocked-scheme path where resolution wasn't run.
    val handlerKind: String? = null,
)

/**
 * Outcome of a [DeepLinkBridge.openUri] attempt.
 *
 * @param success whether the intent was dispatched (not whether the target
 *   finished rendering — the agent verifies that with wait_for + perceive_screen)
 * @param resolvedPackage the package the intent was pinned to / resolved to
 * @param error short reason on failure (blocked scheme, no handler, threw)
 */
data class OpenUriResult(
    val success: Boolean,
    val resolvedPackage: String?,
    val error: String?,
)
