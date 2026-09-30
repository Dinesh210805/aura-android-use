package com.aura.aura_ui.mcp.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.aura.aura_ui.data.deeplink.AppActionSynthesizer
import com.aura.aura_ui.data.deeplink.DeepLinkManager
import com.aura.aura_ui.data.deeplink.DiscoveredLinkSynthesizer
import com.aura.aura_ui.data.deeplink.ManifestIntentFilterReader
import com.aura.aura_ui.data.deeplink.ShortcutEntrySynthesizer
import com.aura.aura_ui.data.deeplink.StaticShortcutInfo
import com.aura.aura_ui.data.deeplink.StaticShortcutReader
import com.aura.aura_ui.utils.AgentLogger
import com.aura.mcp.bridge.DeepLinkBridge
import com.aura.mcp.bridge.DeepLinkCatalog
import com.aura.mcp.bridge.DeepLinkEntry
import com.aura.mcp.bridge.OpenUriResult
import com.aura.mcp.bridge.UriResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.aura.aura_ui.services.startActivityAsAura

/**
 * `:app` binding for [DeepLinkBridge].
 *
 * Discovery is layered weakest-last, every entry trust-tagged:
 *  1. **verified** — https domains the app owns ([DeepLinkManager.getVerifiedDomains],
 *     API 31+). The docs-correct source for App Links.
 *  2. **shortcut** — the app's own static shortcuts (`shortcuts.xml`), surfaced
 *     as synthetic `app-shortcut://<pkg>/<id>` URIs. Developer-curated intents;
 *     [openUri] resolves them back to the app-authored intent and fires that.
 *  3. **resolved / catalog** — curated templates from `assets/deeplink_catalog.json`.
 *     Each is resolve-probed on *this* device: if a concrete form resolves it's
 *     tagged `resolved`, otherwise `catalog` (known shape, unconfirmed here).
 *  4. **discovered** — VIEW intent-filters read from the app's binary manifest
 *     ([ManifestIntentFilterReader]), resolve-probed; non-resolving ones dropped.
 *
 * Invocation ([openUri]) is guarded: scheme allowlist (no `intent:`/`file:`/
 * `content:`/`javascript:`), package pinning to block scheme hijack, no
 * agent-supplied extras (ACTION_VIEW + URI only — for shortcuts, the intent
 * comes verbatim from the target app's own manifest, never from the agent),
 * and query strings redacted from logs.
 */
class AppDeepLinkBridge(
    context: Context,
    private val deepLinkManager: DeepLinkManager,
    private val manifestReader: ManifestIntentFilterReader = ManifestIntentFilterReader(context),
    private val shortcutReader: StaticShortcutReader = StaticShortcutReader(context),
) : DeepLinkBridge {

    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager

    /** Curated catalog (`assets/deeplink_catalog.json`), parsed once. */
    private val catalog: Map<String, CatalogApp> by lazy { loadCatalog() }

    override suspend fun listDeepLinks(packageName: String): DeepLinkCatalog =
        withContext(Dispatchers.IO) {
            val appName = resolveAppName(packageName)
            // Keyed by example URI so verified/catalog duplicates collapse.
            val entries = LinkedHashMap<String, DeepLinkEntry>()

            // Tier 1 — verified https domains (highest trust).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (host in deepLinkManager.getVerifiedDomains(packageName)) {
                    val uri = "https://$host/"
                    entries[uri] = DeepLinkEntry(
                        exampleUri = uri,
                        uriTemplate = null,
                        scheme = "https",
                        host = host,
                        label = "Open $host",
                        source = "verified",
                        requiresConfirmation = false,
                    )
                }
            }

            // Tier 2 — the app's own static shortcuts (developer-curated intents).
            for (entry in ShortcutEntrySynthesizer.synthesize(packageName, shortcutReader.read(packageName))) {
                entries.putIfAbsent(entry.exampleUri, entry)
            }

            // Tier 2b — App Action <capability> declarations from the same
            // shortcuts.xml. Developer-authored AND parameterized (they carry an
            // RFC 6570 url-template), so they rank alongside shortcuts and above
            // anything we infer ourselves. Some apps declare only these and no
            // <shortcut> at all, in which case this is their entire catalog.
            for (entry in AppActionSynthesizer.synthesize(
                packageName,
                shortcutReader.readCapabilities(packageName),
            )) {
                entries.putIfAbsent(entry.exampleUri, entry)
            }

            // Tier 3 — curated catalog, resolve-probed on this device.
            catalog[packageName]?.entries?.forEach { c ->
                val probe = c.example ?: c.uri ?: return@forEach
                if (entries.containsKey(probe)) return@forEach
                val resolves = resolveTarget(probe, packageName) != null
                entries[probe] = DeepLinkEntry(
                    exampleUri = probe,
                    uriTemplate = c.uriTemplate,
                    scheme = schemeOf(probe),
                    host = hostOf(probe),
                    label = c.label,
                    source = if (resolves) "resolved" else "catalog",
                    requiresConfirmation = c.requiresConfirmation,
                )
            }

            // Tier 4 — VIEW filters from the app's binary manifest, lowest trust.
            // Probe each so the agent never receives a dead URI (a scheme-only
            // example won't resolve when the filter also demands a host).
            for (entry in DiscoveredLinkSynthesizer.synthesize(manifestReader.read(packageName))) {
                if (entries.containsKey(entry.exampleUri)) continue
                if (resolveTarget(entry.exampleUri, packageName) == null) continue
                entries[entry.exampleUri] = entry
            }

            DeepLinkCatalog(packageName, appName, entries.values.toList())
        }

    override fun resolveUri(uri: String): UriResolution {
        if (!isAllowed(uri)) {
            return UriResolution(
                resolves = false,
                packageName = null,
                schemeAllowed = false,
                reason = "scheme '${schemeOf(uri) ?: uri.substringBefore('.')}' is not permitted",
            )
        }
        // Synthetic shortcut URI — "resolves" means the shortcut exists.
        parseShortcutUri(uri)?.let { (pkg, id) ->
            val found = findShortcut(pkg, id) != null
            return UriResolution(
                resolves = found,
                packageName = if (found) pkg else null,
                schemeAllowed = true,
                reason = if (found) null else "no static shortcut '$id' in $pkg",
                handlerKind = if (found) "app" else "none",
            )
        }
        // Classify every handler on THIS device rather than trusting
        // resolveActivity, which returns the system chooser for an unpinned web
        // link and used to be discarded as "no handler" (F1). A verified App
        // Link now reports resolves=true, handler_kind="app".
        val classified = classifyHandlers(uri, pinPackage = null)
        return UriResolution(
            resolves = classified.kind != HandlerKind.NONE,
            packageName = classified.appPackage,
            schemeAllowed = true,
            reason = when (classified.kind) {
                HandlerKind.APP -> null
                HandlerKind.BROWSER_ONLY ->
                    "only a browser handles this URI — it will not open inside a specific app"
                HandlerKind.NONE -> "no installed app handles this URI"
            },
            handlerKind = when (classified.kind) {
                HandlerKind.APP -> "app"
                HandlerKind.BROWSER_ONLY -> "browser_only"
                HandlerKind.NONE -> "none"
            },
        )
    }

    override fun openUri(uri: String, packageName: String?): OpenUriResult {
        if (!isAllowed(uri)) {
            val scheme = schemeOf(uri) ?: uri.substringBefore('.')
            AgentLogger.Deeplink.w("open_deeplink rejected disallowed scheme '$scheme'")
            return OpenUriResult(false, null, "scheme '$scheme' is not permitted")
        }

        // Synthetic shortcut URI — rebuild the intent from the target app's own
        // shortcuts.xml and fire that. The agent supplies only identifiers.
        parseShortcutUri(uri)?.let { (pkg, id) -> return openShortcut(pkg, id, packageName) }

        val intent = buildIntent(uri)
            ?: return OpenUriResult(false, null, "could not build intent for '${redact(uri)}'")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Pin to the target app when known — blocks another app that registered
        // the same scheme from hijacking the open. System actions aren't
        // package-scoped, so don't pin those.
        if (packageName != null && !isAction(uri)) intent.setPackage(packageName)

        // Firing is more permissive than discovery: a browser IS a valid target
        // for a web link the user asked for. Only refuse when NOTHING handles it.
        val classified = classifyHandlers(uri, packageName)
        if (classified.kind == HandlerKind.NONE) {
            return OpenUriResult(
                false, null,
                "no handler for this URI" + if (packageName != null) " in $packageName" else "",
            )
        }
        val target = classified.appPackage ?: packageName

        return runCatching {
            appContext.startActivityAsAura(intent)
            AgentLogger.Deeplink.i(
                "Opened deep link",
                mapOf("target" to redact(uri), "package" to (target ?: "browser")),
            )
            OpenUriResult(true, target, null)
        }.getOrElse { t ->
            OpenUriResult(false, null, (t.message ?: t::class.simpleName ?: "open failed").take(120))
        }
    }

    // ── static-shortcut firing ──────────────────────────────────────────────

    /** `app-shortcut://<pkg>/<id>` → (pkg, id); null for any other URI. */
    private fun parseShortcutUri(uri: String): Pair<String, String>? {
        if (schemeOf(uri) != ShortcutEntrySynthesizer.SCHEME) return null
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        val pkg = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val id = parsed.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return pkg to id
    }

    /** Re-read the app's shortcuts and return the fireable one matching [id]. */
    private fun findShortcut(pkg: String, id: String): StaticShortcutInfo? =
        shortcutReader.read(pkg).firstOrNull { s ->
            s.id == id && s.enabled && !s.action.isNullOrBlank() &&
                (s.targetPackage == null || s.targetPackage == pkg)
        }

    private fun openShortcut(pkg: String, id: String, pinPackage: String?): OpenUriResult {
        if (pinPackage != null && pinPackage != pkg) {
            return OpenUriResult(false, null, "shortcut URI targets $pkg but package_name pins $pinPackage")
        }
        val shortcut = findShortcut(pkg, id)
            ?: return OpenUriResult(false, null, "no static shortcut '$id' in $pkg")

        // Intent contents come verbatim from the target app's own manifest —
        // pinned to that package so nothing else can claim it.
        val intent = Intent(shortcut.action).apply {
            shortcut.dataUri?.let { data = Uri.parse(it) }
            setPackage(pkg)
            shortcut.targetClass?.let { setClassName(pkg, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Explicit-component intent: queryIntentActivities only returns EXPORTED
        // activities, and shortcut intents routinely target non-exported ones.
        // Report that precisely (F6) instead of a bare "does not resolve" — the
        // proper API for those is LauncherApps.startShortcut(), which needs the
        // launcher/assistant role we deliberately don't hold.
        if (queryIntentActivities(intent).isEmpty()) {
            val why = if (shortcut.targetClass != null) {
                "shortcut '$id' in $pkg targets a non-exported or missing activity " +
                    "(${shortcut.targetClass}) — it cannot be launched without the launcher role"
            } else {
                "shortcut '$id' in $pkg does not resolve to an activity"
            }
            return OpenUriResult(false, null, why)
        }

        return runCatching {
            appContext.startActivityAsAura(intent)
            AgentLogger.Deeplink.i(
                "Opened app shortcut",
                mapOf("package" to pkg, "shortcut" to id),
            )
            OpenUriResult(true, pkg, null)
        }.getOrElse { t ->
            OpenUriResult(false, null, (t.message ?: t::class.simpleName ?: "open failed").take(120))
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A system action target (e.g. `android.settings.WIFI_SETTINGS`) has no colon. */
    private fun isAction(target: String): Boolean =
        !target.contains(":") && target.contains(".")

    private fun isAllowed(target: String): Boolean {
        if (isAction(target)) return target.startsWith(SETTINGS_ACTION_PREFIX)
        val scheme = schemeOf(target)?.lowercase() ?: return false
        return scheme !in BLOCKED_SCHEMES
    }

    private fun schemeOf(target: String): String? =
        if (isAction(target)) null else runCatching { Uri.parse(target).scheme }.getOrNull()

    private fun hostOf(target: String): String? =
        if (isAction(target)) null else runCatching { Uri.parse(target).host }.getOrNull()

    private fun buildIntent(target: String): Intent? = runCatching {
        if (isAction(target)) Intent(target) else Intent(Intent.ACTION_VIEW, Uri.parse(target))
    }.getOrNull()

    /**
     * Resolve [target] to a REAL app package without firing it; null when only a
     * browser or the chooser handles it (or nothing does). Used by the discovery
     * probes, which want "does a specific app take this" — a browser-only match
     * is not a usable deep link there.
     */
    private fun resolveTarget(target: String, pinPackage: String?): String? {
        if (!isAllowed(target)) return null
        val classified = classifyHandlers(target, pinPackage)
        return if (classified.kind == HandlerKind.APP) classified.appPackage else null
    }

    // ── handler classification (F1) ──────────────────────────────────────────

    private enum class HandlerKind { APP, BROWSER_ONLY, NONE }

    private data class Classified(val kind: HandlerKind, val appPackage: String?)

    /**
     * Enumerate every activity that handles [target] on this device and classify.
     * Replaces the old resolveActivity single-shot, which returned the system
     * chooser (`ResolverActivity`) for an unpinned web link and was discarded as
     * "no handler" — the root cause of resolve_deeplink false-negatives (F1).
     *
     * A pinned probe ([pinPackage] set) narrows to that app up front. An unpinned
     * probe partitions handlers into the target app vs. generic browsers, so a
     * verified App Link is reported as APP even though a browser also matches.
     */
    private fun classifyHandlers(target: String, pinPackage: String?): Classified {
        val intent = buildIntent(target) ?: return Classified(HandlerKind.NONE, null)
        if (pinPackage != null && !isAction(target)) intent.setPackage(pinPackage)

        val infos = queryIntentActivities(intent)
        if (infos.isEmpty()) return Classified(HandlerKind.NONE, null)

        val browsers = browserPackages()
        var appPackage: String? = null
        var sawBrowser = false
        for (info in infos) {
            val pkg = info.activityInfo?.packageName ?: continue
            // The system chooser is not a real handler.
            if (pkg == "android" && info.activityInfo?.name?.contains("ResolverActivity") == true) {
                sawBrowser = true
                continue
            }
            if (pkg in browsers) {
                sawBrowser = true
            } else if (appPackage == null) {
                appPackage = pkg
            }
        }
        return when {
            appPackage != null -> Classified(HandlerKind.APP, appPackage)
            sawBrowser -> Classified(HandlerKind.BROWSER_ONLY, null)
            else -> Classified(HandlerKind.NONE, null)
        }
    }

    private fun queryIntentActivities(intent: Intent) =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())

    /**
     * Packages that are generic web browsers on THIS device — never hardcoded.
     * Probed once with a bare `http:` BROWSABLE intent (the canonical "who is a
     * browser" query) and memoised for the process; a browser install/uninstall
     * mid-session at worst mislabels one entry, never crashes.
     */
    private val browserPackagesCache: Set<String> by lazy {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://_/")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        queryIntentActivities(probe)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun browserPackages(): Set<String> = browserPackagesCache

    private fun resolveAppName(packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** Strip the query string before logging — it may carry tokens or PII. */
    private fun redact(target: String): String =
        if (isAction(target)) target else target.substringBefore('?')

    private fun loadCatalog(): Map<String, CatalogApp> = runCatching {
        val json = appContext.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        val apps = JSONObject(json).getJSONObject("apps")
        val out = LinkedHashMap<String, CatalogApp>()
        for (pkg in apps.keys()) {
            val appObj = apps.getJSONObject(pkg)
            val arr = appObj.getJSONArray("entries")
            val list = ArrayList<CatalogEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                list.add(
                    CatalogEntry(
                        label = e.getString("label"),
                        uri = e.optString("uri", null),
                        uriTemplate = e.optString("uri_template", null),
                        example = e.optString("example", null),
                        requiresConfirmation = e.optBoolean("requires_confirmation", false),
                    ),
                )
            }
            out[pkg] = CatalogApp(appObj.optString("app_name", pkg), list)
        }
        out
    }.getOrElse {
        AgentLogger.Deeplink.e("Failed to load deeplink catalog asset", it)
        emptyMap()
    }

    private data class CatalogApp(val appName: String, val entries: List<CatalogEntry>)

    private data class CatalogEntry(
        val label: String,
        val uri: String?,
        val uriTemplate: String?,
        val example: String?,
        val requiresConfirmation: Boolean,
    )

    internal companion object {
        const val CATALOG_ASSET = "deeplink_catalog.json"
        const val SETTINGS_ACTION_PREFIX = "android.settings."

        /** Single source shared with discovery so the two lists can't drift. */
        val BLOCKED_SCHEMES = DiscoveredLinkSynthesizer.BLOCKED_SCHEMES
    }
}
