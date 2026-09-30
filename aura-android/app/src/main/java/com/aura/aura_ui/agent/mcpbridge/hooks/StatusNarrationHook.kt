package com.aura.aura_ui.agent.mcpbridge.hooks

import com.aura.aura_ui.agent.llm.AgentTraceTap
import com.aura.aura_ui.services.AgentStatusRegistry
import com.aura.aura_ui.services.AgentStatusRegistry.Beat
import com.aura.aura_ui.services.AgentStatusRegistry.Kind
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns each tool call into one line of the on-screen status strip — and the same line into the
 * run's log, which is what the Logs screen's Story view reads (spec 2026-09-23 §3.8).
 *
 * ### Two parts, because a tap alone means nothing
 *
 * - **Headline** — where the run is: the plan step this action serves, `Step 2 of 4 · Open the
 *   chat`, read from the run ledger. "Tapped Amma" is noise; under "Step 2 of 4 · Open the chat"
 *   it is progress.
 * - **Beat** — what just happened, named with what is really on screen, and a [Kind] that picks
 *   its icon. Only [Kind.HOLD] (the user is needed, or AURA stopped for safety) gets colour.
 *
 * ### Where the words come from
 *
 * From what actually came back, never from the request. Screen reads carry `ScreenPayload`'s
 * `SEEN:` header naming the app and its most prominent labels; screen-changing tools carry
 * `post_action_observation` with the settled app, the top labels and whether anything changed.
 * So the line names real things, and can say "nothing moved yet" when that is the truth.
 *
 * ### The rules
 *
 * No jargon: never a som_id, a package, a tool name or an underscore. Never blank: an unlisted
 * tool still gets a plain line. Short: the strip is read at a glance from arm's length.
 */
class StatusNarrationHook(
    private val appLabel: (String) -> String? = { null },
    /** The plan position for the headline; null before a plan exists. */
    private val headline: suspend () -> String? = { null },
    private val publish: (Beat) -> Unit = AgentStatusRegistry::setBeat,
) : PostToolHook, PostToolFailureHook {

    override suspend fun onPostTool(
        toolName: String,
        args: JsonObject,
        result: CallToolResult,
        ctx: HookContext,
    ) {
        emit(narrate(toolName, args, result))
    }

    override suspend fun onPostToolFailure(
        toolName: String,
        args: JsonObject,
        result: CallToolResult,
        ctx: HookContext,
    ) {
        emit(narrateFailure(result))
    }

    private suspend fun emit(beat: Beat) {
        val withHeadline = beat.copy(headline = runCatching { headline() }.getOrNull())
        publish(withHeadline)
        AgentTraceTap.reportNarration(withHeadline.kind.name.lowercase(), withHeadline.text, withHeadline.headline)
    }

    internal fun narrateFailure(result: CallToolResult): Beat {
        val text = allText(result).lowercase()
        return when {
            "paused_by_user" in text -> Beat(Kind.HOLD, "Paused — you have the phone")
            "policy_blocked" in text || "foreground_blocked" in text ->
                Beat(Kind.HOLD, "That's a protected app — it's yours to do")
            "user declined" in text -> Beat(Kind.HOLD, "Okay — not doing that")
            "stale" in text -> Beat(Kind.RECOVER, "The screen moved on — taking a fresh look")
            "not found" in text -> Beat(Kind.RECOVER, "Couldn't find that on screen — looking again")
            else -> Beat(Kind.RECOVER, "That didn't work — trying another way")
        }
    }

    internal fun narrate(toolName: String, args: JsonObject, result: CallToolResult): Beat {
        val obs = PostActionObservationReader.observation(result)
        val seen = seenLine(result)
        val app = (seen?.app ?: PostActionObservationReader.foregroundApp(obs))?.let { friendlyApp(it) }
        val labels = seen?.labels ?: PostActionObservationReader.topLabels(obs, MAX_LABELS)
        val top = labels.firstOrNull()?.take(MAX_LABEL)
        val changed = PostActionObservationReader.screenChanged(obs)
        val landed = when {
            changed == false -> " — nothing moved yet"
            top != null -> " → $top"
            else -> ""
        }

        return when (toolName) {
            "read_screen", "perceive_screen" -> Beat(
                Kind.LOOK,
                when {
                    app != null && labels.isNotEmpty() -> "On $app · ${labels.joinToString(", ")}"
                    app != null -> "Looking at $app"
                    labels.isNotEmpty() -> "Seeing ${labels.joinToString(", ")}"
                    else -> "Taking a look"
                },
            )

            "tap", "double_tap" -> {
                val what = resultField(result, "label")?.take(MAX_LABEL)
                Beat(Kind.ACT, (what?.let { "Tapped $it" } ?: "Tapped") + landed)
            }
            "long_press" -> Beat(
                Kind.ACT,
                (resultField(result, "label")?.let { "Held ${it.take(MAX_LABEL)}" } ?: "Pressed and held") + landed,
            )
            "tap_text" -> Beat(Kind.ACT, (stringArg(args, "text")?.let { "Tapped ${quoted(it, MAX_LABEL)}" } ?: "Tapped") + landed)
            "type_text" -> Beat(Kind.TYPE, stringArg(args, "text")?.let { "Typed ${quoted(it, MAX_TYPED)}" } ?: "Typed it in")
            "press_enter" -> Beat(Kind.ACT, "Pressed enter$landed")
            "press_back" -> Beat(Kind.NAV, app?.let { "Back to $it" } ?: "Went back")
            "press_home" -> Beat(Kind.NAV, "Home screen")
            "scroll_down", "scroll_left", "scroll_right", "swipe" -> Beat(
                Kind.ACT,
                if (changed == false) "Scrolled — that's the end of it" else top?.let { "Scrolled · $it" } ?: "Scrolled",
            )
            "scroll_up" -> Beat(Kind.ACT, if (changed == false) "Already at the top" else "Scrolled back up")

            "launch_app" -> Beat(Kind.NAV, (stringArg(args, "app_name") ?: app)?.let { "Opened $it" } ?: "Opened the app")
            "open_deeplink" -> Beat(Kind.NAV, app?.let { "Jumped straight into $it" } ?: "Followed a shortcut link")
            "list_app_deeplinks" -> Beat(Kind.SEARCH, "Checking for a shortcut into the app")
            "system_intent" -> Beat(
                Kind.NAV,
                stringArg(args, "action")?.let { "Asked the phone to ${prettify(it).lowercase()}" } ?: "Handed that to the phone",
            )

            "browser_open" -> Beat(Kind.NAV, stringArg(args, "url")?.let { "Opened ${host(it)}" } ?: "Opened the browser")
            "browser_find" -> Beat(
                Kind.SEARCH,
                stringArg(args, "query")?.let { "Looking for ${quoted(it, MAX_LABEL)} on the page" } ?: "Looking around the page",
            )
            "browser_read" -> Beat(Kind.LOOK, "Reading the page")
            "browser_act" -> Beat(Kind.ACT, "Working through the page")
            "browser_close" -> Beat(Kind.NAV, "Closed the browser")
            "web_search" -> Beat(Kind.SEARCH, stringArg(args, "query")?.let { "Searched ${quoted(it, MAX_LABEL)}" } ?: "Searched the web")

            "resolve_contact" -> Beat(Kind.SEARCH, stringArg(args, "name")?.let { "Looked up $it" } ?: "Looked up the contact")
            "read_notifications" -> Beat(Kind.LOOK, "Checked your notifications")
            "notification_action" -> Beat(Kind.ACT, "Replied from the notification")
            "media_control" -> Beat(Kind.ACT, stringArg(args, "action")?.let { "Hit ${prettify(it).lowercase()}" } ?: "Changed what's playing")
            "find_files" -> Beat(Kind.SEARCH, stringArg(args, "query")?.let { "Looking for $it" } ?: "Looking for the file")

            "use_skill" -> Beat(Kind.LEARN, stringArg(args, "name")?.let { "Following the $it recipe" } ?: "Following a recipe I know")
            "wait_for" -> Beat(Kind.WORK, "Waiting for the screen to catch up")
            "end_session" -> when (stringArg(args, "outcome")) {
                "partial" -> Beat(Kind.HOLD, "Stopped with part of it done")
                "failure" -> Beat(Kind.HOLD, "Stopped — couldn't finish this one")
                else -> Beat(Kind.DONE, "Done")
            }

            else -> Beat(Kind.WORK, "Working: ${prettify(toolName).lowercase()}")
        }
    }

    // ── reading the result ──────────────────────────────────────────────

    private data class Seen(val app: String?, val labels: List<String>)

    /**
     * Parse `ScreenPayload`'s `SEEN: <package> | label | label` header. First line only; a
     * payload without one (older server, error text) is simply not a screen read.
     */
    private fun seenLine(result: CallToolResult): Seen? {
        val line = result.content.asSequence()
            .filterIsInstance<TextContent>()
            .mapNotNull { it.text }
            .flatMap { it.lineSequence() }
            .firstOrNull { it.startsWith(SEEN_PREFIX) }
            ?: return null
        val parts = line.removePrefix(SEEN_PREFIX)
            .split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return Seen(
            app = parts.first().takeIf { it != "?" },
            labels = parts.drop(1).map { it.take(MAX_LABEL) }.take(MAX_LABELS),
        )
    }

    private fun allText(result: CallToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() }

    private fun stringArg(args: JsonObject, key: String): String? =
        runCatching { args[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun resultField(result: CallToolResult, key: String): String? =
        result.content.asSequence()
            .filterIsInstance<TextContent>()
            .mapNotNull { it.text }
            .filter { key in it }
            .mapNotNull { text ->
                runCatching {
                    json.parseToJsonElement(text).jsonObject[key]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
            .firstOrNull { it.isNotBlank() }

    private fun quoted(text: String, max: Int): String = "“${text.take(max)}”"

    private fun friendlyApp(pkg: String): String =
        appLabel(pkg)?.takeIf { it.isNotBlank() }
            ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').removePrefix("www.").ifBlank { url.take(MAX_LABEL) }

    private fun prettify(toolName: String): String =
        toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_LABEL = 40
        private const val MAX_TYPED = 30

        /** Enough to recognise the screen; more and the line stops being glanceable. */
        private const val MAX_LABELS = 3

        private const val SEEN_PREFIX = "SEEN: "

        /**
         * The headline for a run's position: the plan step in progress (or the next one), with a
         * counter. Null before a plan exists, so a one-action task has no headline at all.
         */
        fun headlineFor(steps: List<Pair<String, Boolean>>): String? {
            if (steps.isEmpty()) return null
            val next = steps.indexOfFirst { !it.second }
            if (next < 0) return "All ${steps.size} steps done · checking"
            return "Step ${next + 1} of ${steps.size} · ${steps[next].first.take(MAX_STEP)}"
        }

        private const val MAX_STEP = 48
    }
}
