package com.aura.aura_ui.agent.memory

/**
 * Coarse goal bucketing for learnings keys (Reflexion-style). Deliberately heuristic -- brittle exact
 * bucketing is a known weakness (agent-learnings §14); a semantic-recall seam is left for later. v1
 * trades precision for zero cost / zero network.
 */
object GoalClassifier {
    fun classify(goal: String): String {
        val g = goal.lowercase()
        return when {
            anyOf(g, "play", "song", "music", "video", "watch", "stream") -> "play_media"
            anyOf(g, "send", "message", "text ", "email", "reply", "whatsapp") -> "send_message"
            anyOf(g, "open", "launch", "start") -> "open_app"
            anyOf(g, "search", "find", "look up", "google") -> "search"
            anyOf(g, "go to", "navigate", "tab", "menu", "settings") -> "navigate"
            else -> "other"
        }
    }

    private fun anyOf(s: String, vararg keys: String) = keys.any { s.contains(it) }
}

/** Maps an utterance / observed tool args to a package name (an `_APP_NAMES`-style table). */
object AppPackageResolver {
    private val APP_NAMES = mapOf(
        "spotify" to "com.spotify.music",
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "gmail" to "com.google.android.gm",
        "chrome" to "com.android.chrome",
        "instagram" to "com.instagram.android",
        "maps" to "com.google.android.apps.maps",
        "settings" to "com.android.settings",
    )

    fun resolve(goal: String, observed: List<String> = emptyList()): String {
        // A real package id observed at runtime (E2 foreground_app, launch_app's
        // package_name arg) beats name-map guessing — this is what keeps Amazon &
        // co. out of the `unknown` bucket. Most frequently observed package wins.
        observed.filter { PACKAGE_ID.matches(it) }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
            ?.let { return it }
        val hay = (observed + goal).joinToString(" ").lowercase()
        APP_NAMES.forEach { (name, pkg) -> if (hay.contains(name) || hay.contains(pkg)) return pkg }
        return "unknown"
    }

    /** Loose Android package-id shape: at least three dot-separated word segments. */
    private val PACKAGE_ID = Regex("""^[A-Za-z][\w]*(\.[\w]+){2,}$""")
}
