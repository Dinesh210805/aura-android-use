package com.aura.aura_ui.agent

/**
 * Single tool→verb map for the notch pill / Live Update chip. Shared by the
 * classic overlay agent path (per-event verbs) and the Gemini Live path (verb
 * recovered from the drive_phone progress string) so the two can never drift —
 * the pill being stuck on one verb ("Weaving") during Live runs was exactly
 * this map living privately in the overlay while Live hardcoded an agent name.
 *
 * `ToolVerbsTest` asserts every registered device tool has a specific verb and
 * that verbs stay pill-short.
 */
object ToolVerbs {

    const val DEFAULT = "Working"

    fun verbFor(tool: String): String = when (tool) {
        "launch_app", "open_deeplink", "open_file" -> "Opening"
        "tap" -> "Tapping"
        "swipe", "scroll" -> "Scrolling"
        "type_text" -> "Typing"
        "press_key" -> "Navigating"
        "press_enter" -> "Submitting"
        "perceive_screen", "get_screenshot", "read_screen" -> "Seeing"
        "verify_action", "wait_for" -> "Checking"
        "web_search" -> "Searching"
        "browser_open", "browser_act" -> "Browsing"
        "browser_read", "browser_screenshot" -> "Reading page"
        "browser_extract" -> "Reading list"
        "browser_upload" -> "Attaching"
        "browser_tabs" -> "Tabs"
        "browser_handoff" -> "Over to you"
        "browser_find" -> "Looking"
        "browser_wait" -> "Waiting"
        "browser_close" -> "Closing"
        "get_device_status", "lookup_app", "watch_device_events",
        "list_app_deeplinks", "resolve_deeplink", "resolve_contact", "find_files" -> "Looking"
        "request_screen_capture_permission" -> "Connecting"
        "volume" -> "Adjusting"
        "read_notifications" -> "Reading"
        "notification_action" -> "Replying"
        "dismiss_notification" -> "Clearing"
        "get_media_sessions", "media_control" -> "Playing"
        "system_intent" -> "Setting"
        "end_session" -> "Finishing"
        "set_plan", "mark_step" -> "Planning"
        "ask_user" -> "Asking"
        "list_skills", "use_skill" -> "Loading"
        else -> DEFAULT
    }
}
