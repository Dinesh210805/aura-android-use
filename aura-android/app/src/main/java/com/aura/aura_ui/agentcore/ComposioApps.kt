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
    data class SignIn(val url: String) : ComposioLink
    data class Failed(val reason: String) : ComposioLink
}

/**
 * Signs single apps in to the user's Composio Connect account, outside any chat turn.
 *
 * - Contract: [check] calls `COMPOSIO_MANAGE_CONNECTIONS` for one toolkit. Composio answers
 *   with the live connection when it is active, otherwise it starts a sign-in and returns its
 *   link (a fresh one each call; old ones expire). The user opens the link; nothing here waits.
 * - Why: that tool is the only way an AURA account starts an app's sign-in (Composio's REST API
 *   needs a developer project key), and the user turning the app on is the approval, so the call
 *   does not go through the safety gate.
 * - Fails: an unreachable server or a refused key comes back as [ComposioLink.Failed].
 * - Reads/Writes: network host of [COMPOSIO_MCP_URL]; the signed-in list via [ConnectorStore].
 */
class ComposioAccounts(private val store: ConnectorStore) {

    fun connector(): ConnectorConfig? = store.configs().firstOrNull { it.url == COMPOSIO_MCP_URL }

    fun signedIn(): Set<String> = connector()?.let { store.signedInApps(it.id) }.orEmpty()

    /** Apps the user turned off; AURA refuses their calls (`ConnectorToolSource.call`). */
    fun turnedOff(): Set<String> = connector()?.let { store.offApps(it.id) }.orEmpty()

    /** Turns [slug] on and asks Composio about it, as [check]. */
    suspend fun turnOn(slug: String): ComposioLink {
        connector()?.let { store.setOffApps(it.id, store.offApps(it.id) - slug) }
        return check(slug)
    }

    /** Turns [slug] off: AURA stops using it. Its sign-in stays at Composio until revoked there. */
    fun turnOff(slug: String) {
        val connector = connector() ?: return
        store.setSignedInApps(connector.id, store.signedInApps(connector.id) - slug)
        store.setOffApps(connector.id, store.offApps(connector.id) + slug)
    }

    suspend fun check(slug: String): ComposioLink {
        val connector = connector() ?: return ComposioLink.Failed("Sign in to Composio first.")
        val app = normalizeSlug(slug) ?: return ComposioLink.Failed("That is not an app name Composio knows.")
        val link = try {
            val result = store.callDirect(connector.id, MANAGE, manageArgs(app))
            if (result.isError) ComposioLink.Failed(result.text.take(200).ifBlank { "Composio refused." }) else parse(result.text, app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ComposioLink.Failed(e.message ?: "Could not reach Composio.")
        }
        val apps = store.signedInApps(connector.id)
        when (link) {
            ComposioLink.Active -> store.setSignedInApps(connector.id, apps + app)
            is ComposioLink.SignIn -> store.setSignedInApps(connector.id, apps - app)
            is ComposioLink.Failed -> Unit
        }
        return link
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
            MANAGE -> (args["toolkits"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.toSet()
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
            val executes = tool in setOf("COMPOSIO_MULTI_EXECUTE_TOOL", "COMPOSIO_EXECUTE_TOOL")
            return if (executes && ConnectorRisk.slugs(args).isEmpty()) off.first() else null
        }

        private fun toolkitArgs(args: JsonObject): List<String> {
            val out = mutableListOf<String>()
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> e.forEach { (k, v) ->
                        if (k == "toolkit" || k == "toolkits" || k == "toolkit_slug") {
                            when (v) {
                                is JsonPrimitive -> v.contentOrNull?.let(out::add)
                                is JsonArray -> v.mapNotNullTo(out) { (it as? JsonPrimitive)?.contentOrNull }
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

        fun manageArgs(slug: String): JsonObject = buildJsonObject {
            putJsonArray("toolkits") { add(JsonPrimitive(slug)) }
            put("reinitiate_all", false)
        }

        /**
         * Reads one toolkit's state from the tool's reply.
         *
         * - Contract: pure. Looks for the toolkit's entry under `data.results` (keyed by slug or a
         *   list item naming it); an `active` status wins, then any https link in the entry, then
         *   any https link in the whole reply. Anything else is [ComposioLink.Failed].
         * - Why so lenient: Composio documents `results` only as "per-toolkit object", and the
         *   reply may come as JSON or as text.
         */
        fun parse(text: String, slug: String): ComposioLink {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            if (root is JsonObject && root["successful"]?.primitiveText() == "false") {
                return ComposioLink.Failed(root["error"]?.primitiveText()?.takeIf { it.isNotBlank() } ?: "Composio refused.")
            }
            val entry = root?.let { entryFor(it, slug) }
            if (entry != null) {
                if (statusOf(entry)?.equals("active", ignoreCase = true) == true) return ComposioLink.Active
                linkIn(entry)?.let { return ComposioLink.SignIn(it) }
            }
            URL.find(text)?.value?.let { return ComposioLink.SignIn(it) }
            if (entry == null && root == null && text.contains("active", ignoreCase = true)) return ComposioLink.Active
            return ComposioLink.Failed(text.take(200).ifBlank { "Composio sent nothing back." })
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

        private fun statusOf(entry: JsonElement): String? =
            (entry as? JsonObject)?.let { it["status"]?.primitiveText() ?: it["connection_status"]?.primitiveText() }

        private fun linkIn(entry: JsonElement): String? = when (entry) {
            is JsonPrimitive -> entry.contentOrNull?.let { URL.find(it)?.value }
            is JsonObject -> entry.entries.sortedByDescending { it.key.contains("redirect") || it.key.contains("link") }
                .firstNotNullOfOrNull { linkIn(it.value) }
            is JsonArray -> entry.firstNotNullOfOrNull { linkIn(it) }
        }

        private fun JsonElement.primitiveText(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}
