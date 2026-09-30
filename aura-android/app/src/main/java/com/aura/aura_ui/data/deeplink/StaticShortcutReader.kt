package com.aura.aura_ui.data.deeplink

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import com.aura.aura_ui.utils.AgentLogger
import org.xmlpull.v1.XmlPullParser

/**
 * Reads a target app's **static shortcuts** (`shortcuts.xml`, referenced by
 * the `android.app.shortcuts` meta-data on a launcher activity).
 *
 * Static shortcuts are developer-curated top entry points ("New chat",
 * "Take selfie") with a ready-made intent — effectively a per-app deeplink
 * catalog maintained by the app vendor. Their XML lives in the app's public
 * resources, so no launcher role or special permission is needed (unlike
 * dynamic/pinned shortcuts, which require `LauncherApps` + default-launcher
 * status and are deliberately out of scope).
 *
 * When a `<shortcut>` declares multiple `<intent>`s, the OS launches the
 * *last* one (earlier ones build the back stack) — this reader keeps the
 * last, matching that behavior.
 */
class StaticShortcutReader(context: Context) {

    private val appContext = context.applicationContext

    fun read(packageName: String): List<StaticShortcutInfo> =
        forEachShortcutsXml(packageName) { parser, res -> parseShortcuts(parser, res) }

    /**
     * App Action `<capability>` declarations from the same `shortcuts.xml`.
     * Separate entry point so callers that only want launcher shortcuts are
     * unaffected; both share one resource walk per call.
     */
    fun readCapabilities(packageName: String): List<AppActionInfo> =
        forEachShortcutsXml(packageName) { parser, res -> parseCapabilities(parser, res) }

    /**
     * Locate every `shortcuts.xml` the app declares (via the
     * `android.app.shortcuts` meta-data resource id — filename-agnostic on
     * purpose: real apps use `shortcut.xml`, `quick_shortcuts_main.xml`, and
     * several `shortcuts_*.xml` files) and run [parse] over each.
     */
    private fun <T> forEachShortcutsXml(
        packageName: String,
        parse: (XmlResourceParser, Resources) -> List<T>,
    ): List<T> = runCatching {
        val pm = appContext.packageManager
        val res = pm.getResourcesForApplication(packageName)
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }

        pkgInfo.activities.orEmpty()
            .mapNotNull { it.metaData?.getInt(SHORTCUTS_META_KEY, 0)?.takeIf { id -> id != 0 } }
            .distinct()
            .flatMap { resId ->
                runCatching {
                    val parser = res.getXml(resId)
                    try {
                        parse(parser, res)
                    } finally {
                        parser.close()
                    }
                }.getOrElse { emptyList() }
            }
    }.getOrElse {
        AgentLogger.Deeplink.w("shortcuts.xml read failed for $packageName: ${it.message}")
        emptyList()
    }

    /**
     * `<capability>` → `<intent>` → `<url-template>` / `<parameter>`. An app's
     * whole shortcuts.xml can be capabilities with no `<shortcut>` at all
     * (Swiggy), which is why this is parsed independently of [parseShortcuts].
     */
    private fun parseCapabilities(parser: XmlResourceParser, res: Resources): List<AppActionInfo> {
        val out = mutableListOf<AppActionInfo>()

        var name: String? = null
        var urlTemplate: String? = null
        var parameterKeys = mutableListOf<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "capability" -> {
                        name = stringAttr(parser, res, "name")
                        urlTemplate = null
                        parameterKeys = mutableListOf()
                    }
                    "parameter" -> if (name != null) {
                        stringAttr(parser, res, "key")?.let { parameterKeys.add(it) }
                    }
                    // Multiple <intent>s under one capability: the last template wins,
                    // matching the launch semantics used for <shortcut> intents.
                    "url-template" -> if (name != null) {
                        urlTemplate = stringAttr(parser, res, "value")
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "capability") {
                    name?.let { out.add(AppActionInfo(it, urlTemplate, parameterKeys.toList())) }
                    name = null
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseShortcuts(parser: XmlResourceParser, res: Resources): List<StaticShortcutInfo> {
        val out = mutableListOf<StaticShortcutInfo>()

        var id: String? = null
        var label: String? = null
        var enabled = true
        var action: String? = null
        var dataUri: String? = null
        var targetPackage: String? = null
        var targetClass: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "shortcut" -> {
                        id = stringAttr(parser, res, "shortcutId")
                        label = stringAttr(parser, res, "shortcutShortLabel")
                            ?: stringAttr(parser, res, "shortcutLongLabel")
                        enabled = booleanAttr(parser, "enabled") ?: true
                        action = null
                        dataUri = null
                        targetPackage = null
                        targetClass = null
                    }
                    // Multiple <intent>s: the last one wins (OS launch semantics).
                    "intent" -> {
                        action = stringAttr(parser, res, "action")
                        dataUri = stringAttr(parser, res, "data")
                        targetPackage = stringAttr(parser, res, "targetPackage")
                        targetClass = stringAttr(parser, res, "targetClass")
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "shortcut") {
                    id?.let {
                        out.add(
                            StaticShortcutInfo(
                                id = it,
                                label = label,
                                targetPackage = targetPackage,
                                action = action,
                                dataUri = dataUri,
                                targetClass = targetClass,
                                enabled = enabled,
                            ),
                        )
                    }
                    id = null
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

    private fun booleanAttr(parser: XmlResourceParser, name: String): Boolean? {
        parser.getAttributeValue(ANDROID_NS, name) ?: return null
        return parser.getAttributeBooleanValue(ANDROID_NS, name, true)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SHORTCUTS_META_KEY = "android.app.shortcuts"
    }
}
