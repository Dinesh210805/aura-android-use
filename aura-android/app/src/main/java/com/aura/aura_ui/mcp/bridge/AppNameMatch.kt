package com.aura.aura_ui.mcp.bridge

/**
 * How well a name someone said matches an installed app — shared by `launch_app`/`lookup_app`
 * ([AppDeviceBridge.lookupApp]) and pre-task research, so the two cannot disagree about which
 * app "Google Maps" is.
 *
 * The package id is part of the app's name for this purpose. Launcher labels drop the brand
 * ("Maps", "Photos", "Messages") while people say it ("Google Maps"); the package keeps it
 * (`com.google.android.apps.maps`). Checking the label alone made `launch_app("Google Maps")`
 * fail on the 2026-09-24 Maps run and cost three extra model calls to recover.
 */
internal object AppNameMatch {

    private val SPLIT = Regex("""[^\p{L}\p{N}]+""")

    fun words(s: String): List<String> = s.lowercase().split(SPLIT).filter { it.isNotEmpty() }

    /**
     * 100 exact label · 80 label starts with it · 60 label contains it · 50 every word of it is a
     * word of the label or package · 40 package contains it · 0 no match.
     */
    fun score(query: String, label: String, packageName: String): Int {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return 0
        val l = label.lowercase()
        return when {
            l == needle -> 100
            l.startsWith(needle) -> 80
            needle in l -> 60
            coversAllWords(needle, label, packageName) -> 50
            needle in packageName.lowercase() -> 40
            else -> 0
        }
    }

    private fun coversAllWords(query: String, label: String, packageName: String): Boolean {
        val have = (words(label) + words(packageName)).toSet()
        return words(query).let { q -> q.isNotEmpty() && q.all { it in have } }
    }

    /** How many distinct words of [text] the app's label or package accounts for. */
    fun wordsCovered(text: String, label: String, packageName: String): Int {
        val have = (words(label) + words(packageName)).toSet()
        return words(text).toSet().count { it in have }
    }
}
