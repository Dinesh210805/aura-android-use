package com.aura.mcp.server

/**
 * Spec 2026-08-02 §7 — what AURA can actually do, in words it can say out loud,
 * **derived from the registered tool set** rather than written by hand.
 *
 * ### Why this exists
 *
 * `AuraCapabilities.text` used to be a hand-written paragraph injected verbatim into both
 * planes, carrying a KDoc that asked contributors to keep it truthful to the real tool
 * surface. That is a manual discipline, and manual disciplines drift. Roughly a dozen
 * `browser_*` tools landed and the voice model never heard of any of them.
 *
 * The consequence was not merely a stale sentence. A model that does not know it can finish
 * a task **hands it back** — deferring is the safe move when you are unsure of your own
 * reach — which is exactly the reported "I've opened it, you do it". Capability drift and
 * the hand-back symptom are the same bug.
 *
 * ### How it cannot drift again
 *
 * The chain is now closed end to end, and every link already fails the build:
 *
 * 1. Register a tool → `ToolNameListsTest` demands a scope classification.
 * 2. Classify it → `McpToolCatalogCoverageTest` demands a description.
 * 3. Describe it → **`CapabilityNarrativeTest` demands a family here.**
 *
 * Add a tool and forget this file, and the build stops. That is the entire design: drift
 * becomes impossible rather than discouraged.
 *
 * ### Constraints on the prose
 *
 * This is **spoken aloud** by the conversation plane, so:
 *  - sentences, never a tool list — `browser_open` read out is gibberish;
 *  - no markdown, no underscores, no digits-as-symbols;
 *  - families with no registered tools are **omitted**, never claimed. Overclaiming is
 *    worse than underclaiming: a model that believes it can do something it cannot will
 *    promise it to the user and fail in front of them.
 */
object CapabilityNarrative {

    /**
     * One spoken capability, and the tools that make it true.
     *
     * [sentence] is a clause, not a full sentence — [narrate] joins them so the result
     * reads as prose rather than a list being recited.
     */
    data class Family(
        val id: String,
        val tools: Set<String>,
        val sentence: String,
    )

    val families: List<Family> = listOf(
        Family(
            id = "screen_control",
            tools = setOf(
                "tap", "double_tap", "long_press", "swipe", "type_text",
                "press_enter", "scroll_up", "scroll_down", "scroll_left", "scroll_right",
                "scroll_to", "press_home", "press_back",
                "open_recent_apps",
            ),
            sentence = "operate any app on the phone by looking at the screen and acting on " +
                "it step by step, the same way a person would",
        ),
        Family(
            id = "perception",
            tools = setOf(
                "perceive_screen", "get_screenshot", "read_screen", "verify_action",
                "wait_for", "watch_device_events", "request_screen_capture_permission",
            ),
            sentence = "see what is on the screen and check that each step actually worked " +
                "before moving on",
        ),
        Family(
            id = "apps",
            tools = setOf(
                "launch_app", "lookup_app", "open_deeplink", "list_app_deeplinks",
                "resolve_deeplink",
            ),
            sentence = "open any installed app, and jump straight to a particular screen " +
                "inside it rather than starting from the beginning",
        ),
        Family(
            id = "web",
            tools = setOf(
                "browser_open", "browser_read", "browser_act", "browser_find",
                "browser_wait", "browser_screenshot", "browser_close", "browser_extract",
                "browser_upload",
                "browser_tabs",
                "browser_handoff",
            ),
            // The family whose absence caused the report. Deliberately concrete: a vague
            // "can use the web" is what let the model think it could only open a page.
            sentence = "browse the web page by page in the background, reading articles, " +
                "filling in forms, comparing prices across sites, and carrying a task " +
                "through to the end without the user watching",
        ),
        Family(
            id = "search",
            tools = setOf("web_search"),
            sentence = "search the web for current information",
        ),
        Family(
            id = "messaging",
            tools = setOf("read_notifications", "notification_action", "dismiss_notification"),
            sentence = "read the user's notifications and reply to messages straight from " +
                "them without opening the app",
        ),
        Family(
            id = "media",
            tools = setOf("media_control", "get_media_sessions", "volume_up", "volume_down", "mute"),
            sentence = "play, pause or skip music and video in any app, and change the volume",
        ),
        Family(
            id = "system",
            tools = setOf("system_intent", "get_device_status"),
            sentence = "set alarms and timers, add calendar events, start a call or a text " +
                "message, share things, and begin navigation",
        ),
        Family(
            id = "personal_data",
            tools = setOf("resolve_contact", "find_files", "open_file"),
            sentence = "look up a contact by name from the user's own contacts, and find and " +
                "open files stored on the phone",
        ),
        Family(
            id = "safety",
            tools = setOf("validate_action", "end_session"),
            sentence = "check whether something is safe before doing it, and stop to ask when " +
                "a task needs a decision only the user can make",
        ),
        Family(
            // Plumbing. Present so the coverage test is a real constraint rather than one
            // with an escape hatch, but contributing no spoken text — the user does not
            // care that a transport handshake exists.
            id = "plumbing",
            tools = setOf("echo", "connect_device", "get_usage_guide"),
            sentence = "",
        ),
    )

    private val describedTools: Set<String> = families.flatMap { it.tools }.toSet()

    /**
     * Registered tools that no family describes.
     *
     * Non-empty means the voice model is about to be wrong about itself, so
     * `CapabilityNarrativeTest` treats it as a build failure.
     */
    fun uncovered(tools: Set<String>): Set<String> = tools - describedTools

    /**
     * Spoken capability prose for exactly the tools that are present.
     *
     * A family counts as present when **any** of its tools is registered: capabilities are
     * described at the level a person cares about ("can browse the web"), and a build
     * shipping six of seven browser tools can still browse.
     */
    fun narrate(tools: Set<String>): String {
        val clauses = families
            .filter { family -> family.sentence.isNotBlank() && family.tools.any { it in tools } }
            .map { it.sentence }

        if (clauses.isEmpty()) return ""

        // Joined as prose, not a bulleted list, because it is heard rather than read. The
        // trailing "and" before the last clause is what stops it sounding like a recital.
        val body = when (clauses.size) {
            1 -> clauses.single()
            else -> clauses.dropLast(1).joinToString("; ") + "; and " + clauses.last()
        }
        return "On this device you can $body."
    }
}
