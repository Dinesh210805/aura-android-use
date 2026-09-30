package com.aura.aura_ui.data.deeplink

import com.aura.mcp.bridge.DeepLinkEntry

/**
 * One `<capability>` (App Action / built-in intent) lifted from a target app's
 * `shortcuts.xml`, flattened to what deep-link synthesis needs. Produced by
 * [StaticShortcutReader]; consumed by [AppActionSynthesizer].
 *
 * Neutral (no Android types) so the synthesis rules stay JVM-testable.
 */
data class AppActionInfo(
    /** Built-in intent name, e.g. `actions.intent.ORDER_MENU_ITEM`. */
    val name: String,
    /** RFC 6570 template, e.g. `swiggy://explore{?dish,restaurant}`. Null = not openable. */
    val urlTemplate: String?,
    /** `android:key` values of the declared `<parameter>`s, e.g. [dish, restaurant]. */
    val parameterKeys: List<String>,
)

/**
 * Turns App Action capabilities into `source = "app_action"` deep-link entries.
 *
 * These were previously invisible: [StaticShortcutReader] only understood
 * `<shortcut>` elements, but a real app's `shortcuts.xml` may be entirely
 * `<capability>` declarations — Swiggy's is, with **zero** `<shortcut>`s. Its
 * one capability carries `swiggy://explore{?dish,restaurant,cuisine,location}`,
 * a ready-made parameterized deep link that we were dropping on the floor.
 * See docs/deeplink-audit/2026-07-24-deeplink-discovery-audit.md (F3).
 *
 * Rules:
 *  - a capability with **no** url-template is skipped — without a template
 *    there is no URI to open, so surfacing it would be pure noise (Telegram
 *    declares GET_THING/GET_ACCOUNT this way)
 *  - the template is passed through verbatim as `uriTemplate`; the agent fills
 *    the `{slots}` and calls resolve_deeplink before opening
 *  - `exampleUri` is the template's stable prefix (everything before the first
 *    `{`), which is a real, openable URI on its own — the app's entry screen
 *  - capabilities that ORDER / SEND / PAY / POST are marked
 *    requiresConfirmation, since firing one can spend money or message someone
 */
object AppActionSynthesizer {

    /**
     * Built-in-intent verbs that can spend money, send a message, or post
     * publicly. Matched on the verb after `actions.intent.`, so this stays
     * generic across apps — no package names are hardcoded.
     */
    private val CONSEQUENTIAL_VERBS = listOf(
        "ORDER", "SEND", "PAY", "BUY", "POST", "CREATE", "START", "CALL", "BOOK",
    )

    fun synthesize(packageName: String, capabilities: List<AppActionInfo>): List<DeepLinkEntry> {
        val byTemplate = LinkedHashMap<String, DeepLinkEntry>()

        for (capability in capabilities) {
            val template = capability.urlTemplate?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (byTemplate.containsKey(template)) continue

            val scheme = template.substringBefore("://", missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() && it.none { c -> c == '{' } }
                ?: continue
            if (scheme.lowercase() in DiscoveredLinkSynthesizer.BLOCKED_SCHEMES) continue

            // Everything before the first {slot} is a concrete, openable URI.
            val example = template.substringBefore('{').trimEnd('?', '&', '/')
                .takeIf { it.isNotEmpty() && it != "$scheme://" }
                ?: template.substringBefore('{')

            byTemplate[template] = DeepLinkEntry(
                exampleUri = example,
                uriTemplate = template,
                scheme = scheme,
                host = example.substringAfter("://", "").substringBefore('/')
                    .takeIf { it.isNotEmpty() },
                label = labelFor(capability),
                source = "app_action",
                requiresConfirmation = isConsequential(capability.name),
            )
        }

        return byTemplate.values.toList()
    }

    /** `actions.intent.ORDER_MENU_ITEM` → "Order menu item" (+ its slot names). */
    private fun labelFor(capability: AppActionInfo): String {
        val verb = capability.name.substringAfterLast('.')
            .split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.lowercase() }
            .replaceFirstChar { it.uppercase() }
        val slots = capability.parameterKeys.filter { it.isNotBlank() }
        return if (slots.isEmpty()) verb else "$verb (${slots.joinToString(", ")})"
    }

    private fun isConsequential(name: String): Boolean {
        val verb = name.substringAfterLast('.').substringBefore('_')
        return CONSEQUENTIAL_VERBS.any { it.equals(verb, ignoreCase = true) }
    }
}
