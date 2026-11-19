package com.aura.aura_ui.agentcore

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Composio Connect: Gmail, Calendar, Drive and more through one endpoint. AURA signs in to it
 * with OAuth (`McpOAuth`); a connector added earlier with a consumer key still works.
 */
const val COMPOSIO_MCP_URL = "https://connect.composio.dev/mcp"

/** The connector id a Composio sign-in is saved under. */
const val COMPOSIO_ID = "composio"

/** An app's logo is this plus its toolkit slug (SVG). */
const val COMPOSIO_LOGO_URL = "https://logos.composio.dev/api/"

/** Where the user removes an app's access at Composio. */
const val COMPOSIO_DASHBOARD_URL = "https://dashboard.composio.dev"

/** One app on the Connectors page. [slug] is Composio's toolkit slug; [color] the tile's ARGB. */
data class ComposioApp(val slug: String, val name: String, val blurb: String, val color: Long)

/** The apps the Connectors page lists first. Any other toolkit slug can be typed into the search. */
val COMPOSIO_APPS = listOf(
    ComposioApp("gmail", "Gmail", "Read, search and send email", 0xFFEA4335),
    ComposioApp("googlecalendar", "Google Calendar", "Events and free time", 0xFF4285F4),
    ComposioApp("googledrive", "Google Drive", "Find and share files", 0xFF0F9D58),
    ComposioApp("googledocs", "Google Docs", "Read and write documents", 0xFF4285F4),
    ComposioApp("googlesheets", "Google Sheets", "Read and update spreadsheets", 0xFF0F9D58),
    ComposioApp("googlemeet", "Google Meet", "Create meetings", 0xFF00897B),
    ComposioApp("outlook", "Outlook", "Microsoft mail and calendar", 0xFF0078D4),
    ComposioApp("slack", "Slack", "Messages and channels", 0xFF4A154B),
    ComposioApp("notion", "Notion", "Pages and databases", 0xFF111111),
    ComposioApp("github", "GitHub", "Issues, pull requests and repos", 0xFF24292F),
    ComposioApp("linear", "Linear", "Issues and projects", 0xFF5E6AD2),
    ComposioApp("todoist", "Todoist", "Tasks and projects", 0xFFE44332),
    ComposioApp("trello", "Trello", "Boards and cards", 0xFF0079BF),
    ComposioApp("asana", "Asana", "Tasks and projects", 0xFFF06A6A),
    ComposioApp("jira", "Jira", "Issues and sprints", 0xFF0052CC),
    ComposioApp("discord", "Discord", "Servers and messages", 0xFF5865F2),
    ComposioApp("dropbox", "Dropbox", "Files and folders", 0xFF0061FF),
    ComposioApp("youtube", "YouTube", "Videos and playlists", 0xFFFF0000),
    ComposioApp("spotify", "Spotify", "Playlists and playback", 0xFF1DB954),
    ComposioApp("twitter", "X (Twitter)", "Posts and messages", 0xFF000000),
    ComposioApp("linkedin", "LinkedIn", "Posts and profile", 0xFF0A66C2),
    ComposioApp("reddit", "Reddit", "Posts and comments", 0xFFFF4500),
    ComposioApp("zoom", "Zoom", "Meetings", 0xFF2D8CFF),
    ComposioApp("airtable", "Airtable", "Bases and records", 0xFF18BFFF),
    ComposioApp("hubspot", "HubSpot", "Contacts and deals", 0xFFFF7A59),
    ComposioApp("calendly", "Calendly", "Scheduling links", 0xFF006BFF),
)

/** Where one app's sign-in stands, as `COMPOSIO_MANAGE_CONNECTIONS` reported it. */
sealed interface ComposioLink {
    data object Active : ComposioLink

    /** Composio has no active account for the app, and no sign-in was started. */
    data object NotConnected : ComposioLink
    data class SignIn(val url: String) : ComposioLink
    data class Failed(val reason: String) : ComposioLink
}

/**
 * Signs single apps in to the user's Composio Connect account, outside any chat turn.
 *
 * - Contract: [sync] and [check] only read: they run the tool's `list` action, which has no side
 *   effects. Only [turnOn] runs `add`, and only when `list` found no active account, because
 *   every `add` creates a new pending account and a fresh sign-in link (Composio caps a toolkit at
 *   10 accounts). The user opens the link; nothing here waits.
 * - Why: that tool is the only way an AURA account starts an app's sign-in (Composio's REST API
 *   needs a developer project key), and the user turning the app on is the approval, so the call
 *   does not go through the safety gate. Composio is the source of truth for what is connected,
 *   so the switches survive an app restart or reinstall; only "turned off" is AURA's own state.
 * - Fails: an unreachable server or a refused key comes back as [ComposioLink.Failed].
 * - Reads/Writes: network host of [COMPOSIO_MCP_URL]; the signed-in and off lists via [ConnectorStore].
 */
class ComposioAccounts(private val store: ConnectorStore) {

    fun connector(): ConnectorConfig? = store.configs().firstOrNull { it.url == COMPOSIO_MCP_URL }

    fun signedIn(): Set<String> = connector()?.let { store.signedInApps(it.id) }.orEmpty()

    /** Apps the user turned off; AURA refuses their calls (`ConnectorToolSource.call`). */
    fun turnedOff(): Set<String> = connector()?.let { store.offApps(it.id) }.orEmpty()

    /**
     * Turns [slug] on: already connected at Composio turns on at once; otherwise starts one
     * sign-in and returns its link.
     */
    suspend fun turnOn(slug: String): ComposioLink {
        val connector = connector() ?: return ComposioLink.Failed("Sign in to Composio first.")
        val app = normalizeSlug(slug) ?: return ComposioLink.Failed("That is not an app name Composio knows.")
        store.setOffApps(connector.id, store.offApps(connector.id) - app)
        val now = check(app)
        if (now != ComposioLink.NotConnected) return now
        return call(connector, addArgs(app)) { parse(it, app) } ?: ComposioLink.Failed("Composio sent nothing back.")
    }

    /** Turns [slug] off: AURA stops using it. Its sign-in stays at Composio until revoked there. */
    fun turnOff(slug: String) {
        val connector = connector() ?: return
        store.setSignedInApps(connector.id, store.signedInApps(connector.id) - slug)
        store.setOffApps(connector.id, store.offApps(connector.id) + slug)
    }

    suspend fun check(slug: String): ComposioLink {
        val app = normalizeSlug(slug) ?: return ComposioLink.Failed("That is not an app name Composio knows.")
        return sync(setOf(app))[app] ?: ComposioLink.Failed("Sign in to Composio first.")
    }

    /**
     * Asks Composio about every app in [slugs] in one `list` call. An active app the user has
     * not turned off joins the signed-in list.
     *
     * - Contract: never removes an app from the signed-in list; only the user's switch does
     *   ([turnOff]). A reply misread once must not switch the user's app off.
     * - Fails: every app maps to the same [ComposioLink.Failed]; empty when signed out of Composio.
     */
    suspend fun sync(slugs: Set<String>): Map<String, ComposioLink> {
        val connector = connector() ?: return emptyMap()
        val apps = slugs.mapNotNull(::normalizeSlug).toSet().ifEmpty { return emptyMap() }
        val links = mutableMapOf<String, ComposioLink>()
        val failed = call(connector, listArgs(apps)) { text ->
            apps.forEach { links[it] = parse(text, it) }
            null
        }
        if (failed != null) return apps.associateWith { failed }
        val active = links.filterValues { it == ComposioLink.Active }.keys - store.offApps(connector.id)
        if (active.isNotEmpty()) store.setSignedInApps(connector.id, store.signedInApps(connector.id) + active)
        return links
    }

    /** Runs the manage tool with [args]; [read] turns a non-error reply into the result. */
    private suspend fun call(connector: ConnectorConfig, args: JsonObject, read: (String) -> ComposioLink?): ComposioLink? = try {
        val result = store.callDirect(connector.id, MANAGE, args)
        if (result.isError) ComposioLink.Failed(result.text.take(200).ifBlank { "Composio refused." }) else read(result.text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ComposioLink.Failed(e.message ?: "Could not reach Composio.")
    }

    companion object {
        const val MANAGE = "COMPOSIO_MANAGE_CONNECTIONS"
        private val SLUG = Regex("[a-z0-9_]{2,40}")
        private val URL = Regex("https://[^\\s\"'<>)\\]]+")

        /** A typed app name as a toolkit slug ("Google Sheets" → "googlesheets"), or null. */
        fun normalizeSlug(text: String): String? =
            text.lowercase().replace(Regex("[\\s-]+"), "").takeIf { it.matches(SLUG) }

        /**
         * The apps a Composio tool call reaches: `toolkits` of a connection call, and the app part
         * of each tool slug (`GMAIL_SEND_EMAIL` → `gmail`) of an execute call. Pure.
         */
        fun appsIn(tool: String, args: JsonObject): Set<String> = when (tool) {
            MANAGE -> (args["toolkits"] as? JsonArray).orEmpty().mapNotNull { toolkitName(it)?.lowercase() }.toSet()
            else -> ConnectorRisk.slugs(args).map { it.substringBefore('_').lowercase() }.filter { it.isNotEmpty() }.toSet()
        }

        /**
         * The turned-off app a call of [tool] reaches, or null when it reaches none.
         *
         * - Contract: pure. Fails closed: each tool slug is matched against every off app as a
         *   whole-word prefix (`GOOGLE_TASKS_LIST` reaches `google_tasks`), the tool name and any
         *   `toolkit`/`toolkits` argument count too, and an execute call that names no tool slug
         *   is refused while any app is off.
         */
        fun blockedApp(tool: String, args: JsonObject, off: Set<String>): String? {
            if (off.isEmpty()) return null
            val names = (ConnectorRisk.slugs(args) + tool + toolkitArgs(args)).map { it.lowercase() }
            off.firstOrNull { app -> names.any { it == app || it.startsWith(app + "_") } }?.let { return it }
            return if (unnamedExecute(tool, args)) off.first() else null
        }

        /** An execute call that names no `tool_slug`, so the app it reaches is unknown. */
        fun unnamedExecute(tool: String, args: JsonObject): Boolean =
            tool in setOf("COMPOSIO_MULTI_EXECUTE_TOOL", "COMPOSIO_EXECUTE_TOOL") && ConnectorRisk.slugs(args).isEmpty()

        private fun toolkitArgs(args: JsonObject): List<String> {
            val out = mutableListOf<String>()
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> e.forEach { (k, v) ->
                        if (k == "toolkit" || k == "toolkits" || k == "toolkit_slug") {
                            when (v) {
                                is JsonPrimitive -> v.contentOrNull?.let(out::add)
                                is JsonArray -> v.mapNotNullTo(out, ::toolkitName)
                                else -> walk(v)
                            }
                        } else {
                            walk(v)
                        }
                    }
                    is JsonArray -> e.forEach(::walk)
                    else -> Unit
                }
            }
            walk(args)
            return out
        }

        /**
         * The manage tool's `list` action for [slugs]: reads each app's accounts, no side effects.
         * - Change together: [addArgs], [toolkitName]; the tool's schema takes `toolkits` as
         *   objects (`name`, `action` = add | rename | list | remove), and `action` defaults to `add`.
         */
        fun listArgs(slugs: Set<String>): JsonObject = buildJsonObject {
            putJsonArray("toolkits") { slugs.forEach { slug -> add(buildJsonObject { put("name", slug); put("action", "list") }) } }
        }

        /** The manage tool's `add` action for [slug]: a new pending account and its sign-in link. */
        fun addArgs(slug: String): JsonObject = buildJsonObject {
            putJsonArray("toolkits") { add(buildJsonObject { put("name", slug); put("action", "add") }) }
        }

        /** A `toolkits` item's app: a plain slug, or the `name` of an item object. */
        private fun toolkitName(item: JsonElement): String? =
            (item as? JsonPrimitive)?.contentOrNull ?: ((item as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

        /**
         * Reads one toolkit's state from the tool's reply.
         *
         * - Contract: pure. A JSON reply is decided by the toolkit's own entry under `data.results`
         *   (keyed by slug or a list item naming it): its status (`active`, `initiated`, `failed`,
         *   found at any depth of the entry), else a sign-in link under a link-named key of the
         *   entry, else `data.summary` when the reply covers one toolkit. Links anywhere else in
         *   the reply (`message`, `session.instructions`, logos) never count. An entry with none
         *   of these (a `list` of an app with no account) is [ComposioLink.NotConnected]. A
         *   plain-text reply is a sign-in when it has a link. Anything else is [ComposioLink.Failed].
         * - Why: Composio documents `results` only as "per-toolkit object". Reading any link in the
         *   reply as a sign-in turned connected apps off on every re-check.
         */
        fun parse(text: String, slug: String): ComposioLink {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                ?: return URL.find(text)?.value?.let { ComposioLink.SignIn(it) }
                    ?: if (text.contains("active", ignoreCase = true)) ComposioLink.Active else ComposioLink.Failed(text.take(200).ifBlank { "Composio sent nothing back." })
            if (root is JsonObject && root["successful"]?.primitiveText() == "false") {
                return ComposioLink.Failed(root["error"]?.primitiveText()?.takeIf { it.isNotBlank() } ?: "Composio refused.")
            }
            val entry = entryFor(root, slug)
            when (entry?.let(::statusOf)?.lowercase()) {
                "active" -> return ComposioLink.Active
                "failed" -> return ComposioLink.Failed(
                    ((entry as? JsonObject)?.get("error_message")?.primitiveText())?.takeIf { it.isNotBlank() } ?: "Composio could not connect $slug.",
                )
            }
            entry?.let(::linkIn)?.let { return ComposioLink.SignIn(it) }
            summaryOf(root)?.let { (total, active) -> if (total == 1 && active == 1) return ComposioLink.Active }
            if (entry != null) return ComposioLink.NotConnected
            return ComposioLink.Failed(text.take(200).ifBlank { "Composio sent nothing back." })
        }

        /** `data.summary` as (total toolkits, active connections), or null. */
        private fun summaryOf(root: JsonElement): Pair<Int, Int>? {
            val summary = ((root as? JsonObject)?.get("data") as? JsonObject)?.get("summary") as? JsonObject ?: return null
            fun count(key: String) = summary[key]?.primitiveText()?.toIntOrNull()
            return (count("total_toolkits") ?: return null) to (count("active_connections") ?: return null)
        }

        private fun entryFor(root: JsonElement, slug: String): JsonElement? {
            val data = (root as? JsonObject)?.get("data") ?: root
            val results = (data as? JsonObject)?.get("results") ?: return null
            return when (results) {
                is JsonObject -> results.entries.firstOrNull { it.key.equals(slug, ignoreCase = true) }?.value
                    ?: results.values.singleOrNull()
                is JsonArray -> results.firstOrNull { item ->
                    (item as? JsonObject)?.values?.any { it.primitiveText()?.equals(slug, ignoreCase = true) == true } == true
                } ?: results.singleOrNull()
                else -> null
            }
        }

        /** The first `status`/`connection_status`/`state` value in [entry], searching nested objects too. */
        private fun statusOf(entry: JsonElement): String? = when (entry) {
            is JsonObject -> STATUS_KEYS.firstNotNullOfOrNull { entry[it]?.primitiveText() }
                ?: entry.values.firstNotNullOfOrNull { statusOf(it) }
            is JsonArray -> entry.firstNotNullOfOrNull { statusOf(it) }
            is JsonPrimitive -> null
        }

        /** A sign-in link: a link-named key's value (redirect, auth link, url), never a logo or docs link. */
        private fun linkIn(entry: JsonElement): String? = when (entry) {
            is JsonPrimitive -> null
            is JsonObject -> entry.entries.firstNotNullOfOrNull { (key, value) ->
                val k = key.lowercase()
                if (LINK_KEY.containsMatchIn(k) && !NOT_LINK_KEY.containsMatchIn(k)) {
                    value.primitiveText()?.let { URL.find(it)?.value } ?: linkIn(value)
                } else {
                    (value as? JsonObject)?.let(::linkIn)
                }
            }
            is JsonArray -> entry.firstNotNullOfOrNull { linkIn(it) }
        }

        private val STATUS_KEYS = listOf("status", "connection_status", "state")
        private val LINK_KEY = Regex("redirect|link|url")
        private val NOT_LINK_KEY = Regex("logo|icon|image|doc")

        private fun JsonElement.primitiveText(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}
