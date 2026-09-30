package com.aura.aura_ui.data.deeplink

import com.aura.mcp.bridge.DeepLinkEntry

/**
 * One `<intent-filter>` lifted from a target app's **binary AndroidManifest**,
 * flattened to the fields deep-link synthesis needs. Produced by
 * [ManifestIntentFilterReader]; consumed by [DiscoveredLinkSynthesizer].
 *
 * Neutral (no Android types) so the synthesis rules are JVM-testable.
 */
data class ManifestFilterInfo(
    val exported: Boolean,
    val actions: List<String>,
    val categories: List<String>,
    val schemes: List<String>,
    val hosts: List<String>,
    val pathPrefixes: List<String>,
    // `android:pathPattern` values. Not emitted as URIs (they're wildcards, not
    // openable), but their presence PROVES a prefix takes a deeper segment —
    // which is what lets us claim a `{id}` template honestly instead of guessing.
    val pathPatterns: List<String> = emptyList(),
)

/**
 * Turns raw manifest intent filters into `source = "discovered"` deep-link
 * entries — the lowest-trust tier of `list_app_deeplinks`.
 *
 * This replaces the dead `DeepLinkManager.discoverAndCacheDeepLinks` path,
 * which probed with a data-less ACTION_VIEW intent and therefore silently
 * dropped every scheme/host filter (Android's data-test rule: a URI-less
 * intent only matches filters that declare no `<data>`). Reading the
 * manifest directly sidesteps resolution entirely.
 *
 * Rules:
 *  - only exported activities with ACTION_VIEW filters — anything else is
 *    not an openable link surface
 *  - blocked schemes (`file:`, `content:`, `intent:`, …) never surface; they
 *    would be refused by `open_deeplink` anyway
 *  - `http`/`https` without a host is junk (matches nothing openable)
 *  - custom schemes rank before web schemes — an app's own scheme is the
 *    real deep-link surface; its https hosts usually duplicate the verified
 *    tier
 *  - output capped: some apps declare hundreds of filters, and the agent
 *    only needs the head of the list
 */
object DiscoveredLinkSynthesizer {

    /** Schemes `open_deeplink` refuses — never worth surfacing. Single source for the bridge too. */
    val BLOCKED_SCHEMES = setOf("intent", "file", "content", "javascript", "data", "android-app")

    const val DEFAULT_MAX_ENTRIES = 20

    /** Cap per (scheme, host) so one sprawling app can't consume the whole budget. */
    const val MAX_PATHS_PER_HOST = 6

    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val WILDCARD_HOST = "*"
    private val WEB_SCHEMES = setOf("http", "https")

    /**
     * First path segments that are auth/checkout/config plumbing rather than
     * places a user asks to go. Demoted, not dropped — if an app has nothing
     * else, a plumbing path still beats no entry. Generic across apps by design;
     * no app-specific package or path is hardcoded here.
     */
    private val PLUMBING_SEGMENTS = setOf(
        "auth", "oauth", "login", "signup", "signin", "logout", "account",
        "accounts", "callback", "consent", "checkout", "payment", "pay",
        "billing", "config", "settings", "api", "r", "redirect", "verify",
    )

    fun synthesize(
        filters: List<ManifestFilterInfo>,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): List<DeepLinkEntry> {
        val byUri = LinkedHashMap<String, DeepLinkEntry>()
        // Per (scheme, host), how many paths we've already emitted — keeps one
        // sprawling app (Spotify declares 92 prefixes) from eating the whole budget.
        val perHostCount = mutableMapOf<String, Int>()

        for (filter in filters) {
            if (!filter.exported) continue
            if (ACTION_VIEW !in filter.actions) continue

            for (rawScheme in filter.schemes) {
                val scheme = rawScheme.lowercase()
                if (scheme in BLOCKED_SCHEMES) continue

                val hosts: List<String?> = filter.hosts.ifEmpty { listOf(null) }
                for (host in hosts) {
                    if (scheme in WEB_SCHEMES && host == null) continue
                    // "*" is a wildcard host: it always resolves to a browser and
                    // is never a real destination.
                    if (host == WILDCARD_HOST) continue

                    // Rank the filter's own prefixes so generic destinations
                    // (/search, /album) beat plumbing (/account/parental-consent/...).
                    // No prefixes at all → the bare host, as before.
                    val paths: List<String?> = filter.pathPrefixes
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sortedWith(compareBy({ if (isPlumbing(it)) 1 else 0 }, { depthOf(it) }))
                        .ifEmpty { listOf(null) }

                    for (path in paths) {
                        val hostKey = "$scheme://$host"
                        if ((perHostCount[hostKey] ?: 0) >= MAX_PATHS_PER_HOST) break

                        val uri = buildString {
                            append(scheme).append("://")
                            if (host != null) {
                                append(host)
                                append(path ?: "/")
                            }
                        }
                        if (byUri.containsKey(uri)) continue

                        byUri[uri] = DeepLinkEntry(
                            exampleUri = uri,
                            // Only claim a {id} slot when a sibling pathPattern proves
                            // this prefix takes a deeper segment. Otherwise null — a
                            // template must be manifest-backed, never a guess.
                            uriTemplate = if (path != null && takesDeeperSegment(path, filter.pathPatterns)) {
                                "$uri/{id}"
                            } else {
                                null
                            },
                            scheme = scheme,
                            host = host,
                            label = "Open $uri",
                            source = "discovered",
                            requiresConfirmation = false,
                        )
                        perHostCount[hostKey] = (perHostCount[hostKey] ?: 0) + 1
                    }
                }
            }
        }

        return byUri.values
            .sortedBy { if (it.scheme in WEB_SCHEMES) 1 else 0 } // stable: keeps manifest order within each band
            .take(maxEntries)
    }

    /** Path segments that are app plumbing, not user destinations. */
    private fun isPlumbing(path: String): Boolean {
        val first = path.trim('/').substringBefore('/').lowercase()
        return first in PLUMBING_SEGMENTS
    }

    private fun depthOf(path: String): Int = path.trim('/').count { it == '/' }

    /**
     * True when some `pathPattern` in the same filter extends [prefix] with at
     * least one more segment — i.e. the manifest itself says this prefix is a
     * parent of per-item URLs, so `<prefix>/{id}` is a real shape.
     */
    private fun takesDeeperSegment(prefix: String, patterns: List<String>): Boolean {
        val normalized = "/" + prefix.trim('/')
        if (normalized == "/") return false
        return patterns.any { pattern ->
            val p = pattern.trim()
            p.startsWith("$normalized/") && p.length > normalized.length + 1
        }
    }
}
