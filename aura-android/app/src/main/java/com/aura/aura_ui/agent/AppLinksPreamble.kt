package com.aura.aura_ui.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The goal's app's known direct links, in front of the model before its first move.
 *
 * On the 2026-09-24 Maps run the agent spent ~5 minutes and ~50 model calls tapping in a route
 * that one link opens (`maps/dir/?api=1&origin=…&waypoints=…`). The link was one tool call away —
 * `list_app_deeplinks` — but the model never made that call, and research notes land after turn 1
 * has already chosen a route. So the catalog entries for the matched app are read here, instantly
 * and offline, and ride the USER channel next to the goal like [ForegroundPreamble] does.
 *
 * Source is AURA's own curated `deeplink_catalog.json`, not the web or the screen.
 */
object AppLinksPreamble {

    private val json = Json { ignoreUnknownKeys = true }

    /** (label, template-or-uri) for [packageName] in the catalog; empty when absent or unreadable. */
    fun catalogEntries(catalogJson: String, packageName: String): List<Pair<String, String>> = runCatching {
        json.parseToJsonElement(catalogJson).jsonObject["apps"]?.jsonObject?.get(packageName)
            ?.jsonObject?.get("entries")?.jsonArray.orEmpty()
            .mapNotNull { e ->
                val o = e.jsonObject
                val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val link = (o["uri_template"] ?: o["uri"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                label to link
            }
    }.getOrDefault(emptyList())

    fun forApp(appLabel: String, entries: List<Pair<String, String>>): String? {
        if (entries.isEmpty()) return null
        return "Direct links for $appLabel (AURA's link catalog). If one fits the goal, fill in its " +
            "{slots} and open it with open_deeplink — one call instead of tapping through the app:\n" +
            entries.joinToString("\n") { (label, link) -> "- $label: $link" }
    }
}
