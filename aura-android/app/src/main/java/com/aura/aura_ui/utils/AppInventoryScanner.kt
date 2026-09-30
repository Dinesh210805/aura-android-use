package com.aura.aura_ui.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.aura.aura_ui.data.AppInfo
import com.aura.aura_ui.data.IntentFilterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans device for installed apps and extracts package names, app names, and deep links
 */
class AppInventoryScanner(private val context: Context) {
    companion object {
        private const val TAG = "AppInventoryScanner"

        // Comprehensive list of deep link schemes to check
        private val DEEP_LINK_SCHEMES =
            listOf(
                // Standard schemes
                "http",
                "https",
                "content",
                "file",
                // Communication
                "tel",
                "sms",
                "smsto",
                "mms",
                "mmsto",
                "mailto",
                // Social media & messaging
                "whatsapp",
                "fb",
                "instagram",
                "twitter",
                "telegram",
                "snapchat",
                "tiktok",
                "linkedin",
                "reddit",
                "discord",
                // Media & entertainment
                "youtube",
                "spotify",
                "netflix",
                "prime",
                "disney",
                "twitch",
                "soundcloud",
                // Productivity
                "zoom",
                "zoomus",
                "meet",
                "teams",
                "slack",
                "notion",
                "evernote",
                "onenote",
                // Maps & navigation
                "geo",
                "maps",
                "google.navigation",
                "waze",
                // Finance & payments
                "upi",
                "paytm",
                "gpay",
                "phonepe",
                "bhim",
                // Shopping
                "amazon",
                "flipkart",
                "myntra",
                "swiggy",
                "zomato",
                // Utilities
                "market",
                "intent",
                "package",
                "settings",
                // Generic app schemes
                "app",
                "apps",
                "android-app",
            )
    }

    /**
     * Outcome of a scan, carrying enough counts to tell a genuinely short app list
     * apart from a scan that quietly fell over.
     *
     * Every failure path in [scan] used to degrade to a shorter list with no signal
     * — the bulk query returned `emptyList()` on error, per-app failures were
     * skipped, and the caller wrapped the whole thing in `getOrElse { emptyList() }`.
     * A total failure and an app-less device rendered identically, which is why a
     * near-empty "Add app" picker went unnoticed.
     */
    data class Report(
        val apps: List<AppInfo>,
        val installedPackages: Int,
        val launcherEntries: Int,
        val skipped: Int,
        val error: String? = null,
    ) {
        /** The OS reported launcher entries we failed to turn into usable apps. */
        val degraded: Boolean
            get() = error != null || apps.count { it.isLaunchable() } < launcherEntries
    }

    /** List-only wrapper for callers that don't inspect scan health. */
    suspend fun scanInstalledApps(): List<AppInfo> = scan().apps

    /**
     * Scan installed apps and extract their information.
     * Uses queryIntentActivities per scheme to build a reverse map —
     * the only reliable approach since package names don't reveal URI schemes.
     */
    suspend fun scan(): Report =
        withContext(Dispatchers.Default) {
            val packageManager = context.packageManager
            val installedApps = mutableListOf<AppInfo>()
            var launcherPackages: Set<String> = emptySet()
            var scanError: String? = null
            var skipped = 0

            try {
                Log.i(TAG, "🔍 Starting app inventory scan...")

                // Launchability in ONE query — the same intent the launcher itself
                // resolves. This replaces getLaunchIntentForPackage() called once per
                // package: on a device with 577 packages that was 577 Binder round
                // trips to answer a question the OS can answer in one.
                launcherPackages = try {
                    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    queryActivities(packageManager, main)
                        .map { it.activityInfo.packageName }
                        .toSet()
                } catch (e: Exception) {
                    Log.e(TAG, "Launcher query failed: ${e.message}", e)
                    emptySet()
                }
                Log.i(TAG, "🚀 Launcher entries reported by the OS: ${launcherPackages.size}")

                // Deliberately NO GET_META_DATA. Nothing in this scanner reads
                // ApplicationInfo.metaData (StaticShortcutReader does its own query),
                // and the flag attaches a Bundle to every record. Across several
                // hundred packages that inflates the reply past the ~1 MB Binder
                // transaction limit, at which point this call throws and the entire
                // app list collapses to empty — which is how a near-empty "Add app"
                // picker shipped without anyone seeing an error.
                val packages =
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            packageManager.getInstalledApplications(
                                PackageManager.ApplicationInfoFlags.of(0L),
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.getInstalledApplications(0)
                        }
                    } catch (e: Exception) {
                        scanError = "installed-app query failed: ${e.message}"
                        Log.e(TAG, scanError, e)
                        emptyList()
                    }
                Log.i(TAG, "📦 Installed packages returned: ${packages.size}")

                // Build reverse map: packageName -> set of schemes it actually handles
                // queryIntentActivities is the only reliable way — package name ≠ URI scheme
                val packageSchemes = mutableMapOf<String, MutableSet<String>>()
                val packageBrowsableFilters = mutableMapOf<String, MutableList<IntentFilterInfo>>()

                for (scheme in DEEP_LINK_SCHEMES) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$scheme://test")).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                        @Suppress("DEPRECATION")
                        val resolveInfos: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)
                        for (info in resolveInfos) {
                            val pkg = info.activityInfo.packageName
                            packageSchemes.getOrPut(pkg) { mutableSetOf() }.add(scheme)
                            packageBrowsableFilters.getOrPut(pkg) { mutableListOf() }.add(
                                IntentFilterInfo(
                                    action = Intent.ACTION_VIEW,
                                    categories = listOf(Intent.CATEGORY_BROWSABLE),
                                    dataScheme = scheme,
                                    dataHost = info.filter?.authoritiesIterator()
                                        ?.takeIf { it.hasNext() }?.next()?.host,
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query scheme $scheme: ${e.message}")
                    }
                }

                Log.i(TAG, "🔗 Scheme query complete: ${packageSchemes.size} apps have deep links")

                for (appInfo in packages) {
                    try {
                        val packageName = appInfo.packageName
                        val appName = try {
                            appInfo.loadLabel(packageManager).toString()
                        } catch (e: Exception) {
                            packageName
                        }
                        val launchable = packageName in launcherPackages
                        // versionName costs a getPackageInfo IPC per app and is only
                        // consumed by the retired Python-backend inventory payload, so
                        // pay for it on the ~150 apps a user can actually open rather
                        // than on all ~577.
                        val versionName = if (!launchable) "" else try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                        } catch (e: Exception) {
                            ""
                        }
                        val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                        val intentFilters = mutableListOf<IntentFilterInfo>()
                        if (launchable) {
                            intentFilters.add(
                                IntentFilterInfo(
                                    action = Intent.ACTION_MAIN,
                                    categories = listOf(Intent.CATEGORY_LAUNCHER),
                                ),
                            )
                        }
                        intentFilters.addAll(packageBrowsableFilters[packageName] ?: emptyList())

                        installedApps.add(
                            AppInfo(
                                packageName = packageName,
                                appName = appName,
                                isSystemApp = isSystemApp,
                                versionName = versionName,
                                deepLinks = (packageSchemes[packageName]?.sorted() ?: emptyList()),
                                intentFilters = intentFilters,
                            ),
                        )
                    } catch (e: Exception) {
                        skipped++
                        Log.w(TAG, "Failed to scan app: ${appInfo.packageName} - ${e.message}")
                    }
                }

                // Safety net: never under-report an app the user can actually open.
                // If the bulk installed-app query degraded (Binder limit, OEM package
                // filtering, per-app skips), the launcher query already told us these
                // packages exist — add them from that result rather than showing the
                // user a picker missing apps they can plainly see on their home screen.
                val seen = installedApps.mapTo(mutableSetOf()) { it.packageName }
                val recovered = launcherPackages.filterNot { it in seen }
                if (recovered.isNotEmpty()) {
                    Log.w(TAG, "⚠️ Recovering ${recovered.size} launchable app(s) the bulk query missed")
                    for (packageName in recovered) {
                        installedApps.add(
                            AppInfo(
                                packageName = packageName,
                                appName = runCatching {
                                    packageManager.getApplicationLabel(
                                        packageManager.getApplicationInfo(packageName, 0),
                                    ).toString()
                                }.getOrDefault(packageName),
                                isSystemApp = false,
                                versionName = "",
                                deepLinks = (packageSchemes[packageName]?.sorted() ?: emptyList()),
                                intentFilters = listOf(
                                    IntentFilterInfo(
                                        action = Intent.ACTION_MAIN,
                                        categories = listOf(Intent.CATEGORY_LAUNCHER),
                                    ),
                                ) + (packageBrowsableFilters[packageName] ?: emptyList()),
                            ),
                        )
                    }
                }

                Log.i(TAG, "✅ Scan complete: ${installedApps.size} apps found")
                Log.i(TAG, "🚀 Launchable: ${installedApps.count { it.isLaunchable() }} (OS reported ${launcherPackages.size})")
                Log.i(TAG, "📱 User apps: ${installedApps.count { !it.isSystemApp }}")
                Log.i(TAG, "⚙️ System apps: ${installedApps.count { it.isSystemApp }}")
                Log.i(TAG, "🔗 Apps with deep links: ${installedApps.count { it.deepLinks.isNotEmpty() }}")
                if (skipped > 0) Log.w(TAG, "⚠️ Skipped $skipped app(s) due to per-app errors")
            } catch (e: Exception) {
                scanError = "scan failed: ${e.message}"
                Log.e(TAG, "❌ App scan failed: ${e.message}", e)
            }

            return@withContext Report(
                apps = installedApps,
                installedPackages = installedApps.size,
                launcherEntries = launcherPackages.size,
                skipped = skipped,
                error = scanError,
            )
        }

    /** API-level split for queryIntentActivities, kept in one place. */
    private fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    // Kept for reference — replaced by queryIntentActivities-based reverse lookup in scanInstalledApps()
    @Suppress("unused")
    private fun extractViewIntents_LEGACY(
        packageManager: PackageManager,
        packageName: String,
    ): ViewIntentResult {
        val deepLinks = mutableSetOf<String>()
        val intentFilters = mutableListOf<IntentFilterInfo>()

        try {
            // Check if app is launchable
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                intentFilters.add(
                    IntentFilterInfo(
                        action = Intent.ACTION_MAIN,
                        categories = listOf(Intent.CATEGORY_LAUNCHER),
                        dataScheme = null,
                        dataHost = null
                    ),
                )
            }

            // Smart pattern matching for popular apps
            val pkgLower = packageName.lowercase()
            
            when {
                // Messaging
                "whatsapp" in pkgLower -> {
                    deepLinks.addAll(listOf("whatsapp", "https", "tel"))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, listOf(Intent.CATEGORY_BROWSABLE), "whatsapp", null))
                }
                "telegram" in pkgLower -> {
                    deepLinks.addAll(listOf("tg", "https", "tel"))
                }
                "snapchat" in pkgLower -> {
                    deepLinks.addAll(listOf("snapchat", "https"))
                }
                "discord" in pkgLower -> {
                    deepLinks.addAll(listOf("discord", "https"))
                }
                
                // Social media
                "facebook" in pkgLower || "fb.apk" in pkgLower -> {
                    deepLinks.addAll(listOf("fb", "https"))
                }
                "instagram" in pkgLower -> {
                    deepLinks.addAll(listOf("instagram", "https"))
                }
                "twitter" in pkgLower || "x.com" in pkgLower -> {
                    deepLinks.addAll(listOf("twitter", "https"))
                }
                "tiktok" in pkgLower -> {
                    deepLinks.addAll(listOf("tiktok", "https"))
                }
                "linkedin" in pkgLower -> {
                    deepLinks.addAll(listOf("linkedin", "https"))
                }
                "reddit" in pkgLower -> {
                    deepLinks.addAll(listOf("reddit", "https"))
                }
                
                // Email
                "gmail" in pkgLower || "android.gm" in pkgLower || "email" in pkgLower -> {
                    deepLinks.addAll(listOf("mailto", "https"))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, emptyList(), "mailto", null))
                }
                
                // Video conferencing
                "zoom" in pkgLower -> {
                    deepLinks.addAll(listOf("zoomus", "tel", "https"))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, listOf(Intent.CATEGORY_BROWSABLE), "zoomus", "zoom.us"))
                }
                "meet" in pkgLower && "google" in pkgLower -> {
                    deepLinks.addAll(listOf("https"))
                }
                "teams" in pkgLower && "microsoft" in pkgLower -> {
                    deepLinks.addAll(listOf("msteams", "https"))
                }
                
                // Maps
                "maps" in pkgLower || "navigation" in pkgLower -> {
                    deepLinks.addAll(listOf("geo", "https"))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, listOf(Intent.CATEGORY_BROWSABLE), "geo", null))
                }
                
                // Browsers
                "chrome" in pkgLower || "browser" in pkgLower || "brave" in pkgLower || "firefox" in pkgLower -> {
                    deepLinks.addAll(listOf("http", "https"))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, listOf(Intent.CATEGORY_BROWSABLE), "http", null))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, listOf(Intent.CATEGORY_BROWSABLE), "https", null))
                }
                
                // Phone & SMS
                "dialer" in pkgLower || "phone" in pkgLower || "contacts" in pkgLower || "truecaller" in pkgLower -> {
                    deepLinks.addAll(listOf("tel", "sms"))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, emptyList(), "tel", null))
                }
                "message" in pkgLower || "sms" in pkgLower -> {
                    deepLinks.addAll(listOf("sms", "smsto"))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, emptyList(), "sms", null))
                }
                
                // Media
                "youtube" in pkgLower -> {
                    deepLinks.addAll(listOf("youtube", "https"))
                }
                "spotify" in pkgLower -> {
                    deepLinks.addAll(listOf("spotify", "https"))
                }
                "netflix" in pkgLower -> {
                    deepLinks.addAll(listOf("netflix", "https"))
                }
                
                // Payments
                "paytm" in pkgLower || "phonepe" in pkgLower || "gpay" in pkgLower || "bhim" in pkgLower -> {
                    deepLinks.addAll(listOf("upi", "https"))
                    intentFilters.add(IntentFilterInfo(Intent.ACTION_VIEW, emptyList(), "upi", null))
                }
            }

            // Fallback: generic app scheme for launchable apps
            if (deepLinks.isEmpty() && launchIntent != null) {
                deepLinks.add("app")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract deep links for $packageName: ${e.message}")
        }

        return ViewIntentResult(
            deepLinks = deepLinks.toList().sorted(),
            intentFilters = intentFilters,
        )
    }

    /**
     * Helper class for VIEW intent results
     */
    private data class ViewIntentResult(
        val deepLinks: List<String>,
        val intentFilters: List<IntentFilterInfo>,
    )
}

/**
 * True if this app has a launcher entry — the user can actually open it from
 * the app drawer, whether or not it's flagged `FLAG_SYSTEM`.
 *
 * [AppInfo.isSystemApp] means "shipped in the system partition" — nothing
 * more. OEMs routinely preload real, user-facing financial apps this way
 * (Google Pay, carrier wallets, the phone maker's own payment app), so
 * filtering by `isSystemApp` silently drops apps a security feature must not
 * drop. Launchability is the correct signal for "the user can interact with
 * this app" — used by the Restricted Apps scan/add flow instead.
 */
fun AppInfo.isLaunchable(): Boolean =
    intentFilters.any { it.action == Intent.ACTION_MAIN }
