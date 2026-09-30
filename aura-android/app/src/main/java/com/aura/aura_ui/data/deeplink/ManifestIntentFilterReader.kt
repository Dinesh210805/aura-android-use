package com.aura.aura_ui.data.deeplink

import android.content.Context
import android.content.res.Resources
import android.content.res.XmlResourceParser
import com.aura.aura_ui.utils.AgentLogger
import org.xmlpull.v1.XmlPullParser

/**
 * Reads `<intent-filter>` declarations straight out of a target app's
 * **binary AndroidManifest.xml** (via [Context.createPackageContext] +
 * `AssetManager.openXmlResourceParser`) — the same technique launchers use.
 *
 * This is the docs-correct way to enumerate another app's deep-link surface:
 * `PackageManager.queryIntentActivities` with a URI-less probe can never
 * return scheme/host filters (Android's data-test rule), and there is no
 * public API that lists them. Reading the manifest sidesteps resolution.
 *
 * Only `<activity>` / `<activity-alias>` filters are collected; providers,
 * receivers and services are not openable link surfaces. All framework
 * failures degrade to an empty list — discovery is best-effort by design.
 */
class ManifestIntentFilterReader(context: Context) {

    private val appContext = context.applicationContext

    fun read(packageName: String): List<ManifestFilterInfo> = runCatching {
        val targetContext = appContext.createPackageContext(packageName, 0)
        val parser = targetContext.assets.openXmlResourceParser("AndroidManifest.xml")
        try {
            parse(parser, targetContext.resources)
        } finally {
            parser.close()
        }
    }.getOrElse {
        AgentLogger.Deeplink.w("manifest intent-filter read failed for $packageName: ${it.message}")
        emptyList()
    }

    private fun parse(parser: XmlResourceParser, res: Resources): List<ManifestFilterInfo> {
        val out = mutableListOf<ManifestFilterInfo>()

        var inActivity = false
        var activityDepth = 0
        var exportedAttr: Boolean? = null
        val pendingFilters = mutableListOf<PendingFilter>()

        var current: PendingFilter? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "activity", "activity-alias" -> {
                        inActivity = true
                        activityDepth = parser.depth
                        exportedAttr = booleanAttr(parser, "exported")
                        pendingFilters.clear()
                    }
                    "intent-filter" -> if (inActivity) current = PendingFilter()
                    "action" -> current?.actions?.addIfNotNull(stringAttr(parser, res, "name"))
                    "category" -> current?.categories?.addIfNotNull(stringAttr(parser, res, "name"))
                    "data" -> current?.let { f ->
                        f.schemes.addIfNotNull(stringAttr(parser, res, "scheme"))
                        f.hosts.addIfNotNull(stringAttr(parser, res, "host"))
                        f.pathPrefixes.addIfNotNull(
                            stringAttr(parser, res, "pathPrefix") ?: stringAttr(parser, res, "path"),
                        )
                        // Wildcards — never emitted as URIs, but they prove which
                        // prefixes take a deeper {id} segment (see synthesizer).
                        f.pathPatterns.addIfNotNull(stringAttr(parser, res, "pathPattern"))
                    }
                }
                XmlPullParser.END_TAG -> when {
                    parser.name == "intent-filter" && current != null -> {
                        pendingFilters.add(current)
                        current = null
                    }
                    (parser.name == "activity" || parser.name == "activity-alias") &&
                        inActivity && parser.depth == activityDepth -> {
                        // Legacy default: an activity with at least one intent
                        // filter was exported unless it said otherwise. API 31+
                        // apps must declare it explicitly, so the fallback only
                        // fires for old targets.
                        val exported = exportedAttr ?: pendingFilters.isNotEmpty()
                        for (f in pendingFilters) {
                            out.add(
                                ManifestFilterInfo(
                                    exported = exported,
                                    actions = f.actions,
                                    categories = f.categories,
                                    schemes = f.schemes,
                                    hosts = f.hosts,
                                    pathPrefixes = f.pathPrefixes,
                                    pathPatterns = f.pathPatterns,
                                ),
                            )
                        }
                        inActivity = false
                        pendingFilters.clear()
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /** Attribute that may be inline or a resource reference into the target app. */
    private fun stringAttr(parser: XmlResourceParser, res: Resources, name: String): String? {
        val resId = parser.getAttributeResourceValue(ANDROID_NS, name, 0)
        if (resId != 0) return runCatching { res.getString(resId) }.getOrNull()
        return parser.getAttributeValue(ANDROID_NS, name)
    }

    /** Tri-state boolean attribute: null when absent (caller applies the default). */
    private fun booleanAttr(parser: XmlResourceParser, name: String): Boolean? {
        parser.getAttributeValue(ANDROID_NS, name) ?: return null
        return parser.getAttributeBooleanValue(ANDROID_NS, name, false)
    }

    private fun MutableList<String>.addIfNotNull(value: String?) {
        if (!value.isNullOrBlank()) add(value)
    }

    private class PendingFilter {
        val actions = mutableListOf<String>()
        val categories = mutableListOf<String>()
        val schemes = mutableListOf<String>()
        val hosts = mutableListOf<String>()
        val pathPrefixes = mutableListOf<String>()
        val pathPatterns = mutableListOf<String>()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
