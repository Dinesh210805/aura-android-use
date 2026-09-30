package com.aura.mcp.server

import com.aura.mcp.bridge.McpScope

/** One tool's public metadata: its name, capability scope, and plain-language copy. */
data class McpToolInfo(
    val name: String,
    val scope: McpScope,
    val description: String,
)

/**
 * The single source of truth the **Tools reference screen** renders. It joins the
 * policy layer's scope classification ([McpToolScopes.toolScopeMap]) with a plain-
 * language description per tool, so the UI never keeps its own tool list.
 *
 * Adding a tool: register it (as usual), classify its scope in [McpToolScopes] —
 * which you must do anyway — and add one line to [descriptions] here. The new tool
 * then appears in the app automatically with no UI edit. A tool with no description
 * still lists (it falls back to its name), and [McpToolCatalogCoverageTest] fails
 * loudly so the copy never silently goes stale.
 */
object McpToolCatalog {

    /** Short human copy per tool. Keep in sync with the registered tool set. */
    private val descriptions: Map<String, String> = mapOf(
        "echo" to "Connectivity check — echoes input back",
        "get_usage_guide" to "The full how-to-drive-this-device playbook",
        "get_device_status" to "Battery, network and device state",
        "get_screenshot" to "Capture the current screen",
        "request_screen_capture_permission" to "Ask you to allow screen capture",
        "read_screen" to "Read the on-screen UI structure",
        "watch_device_events" to "Observe screen changes as they happen",
        "perceive_screen" to "See and understand the current screen",
        "verify_action" to "Check whether the last action worked",
        "wait_for" to "Wait for something to appear on screen",
        "end_session" to "Mark the task as finished",
        "lookup_app" to "Find an installed app",
        "web_search" to "Search the web",
        "browser_open" to "Open a web page and read it",
        "browser_read" to "Re-read the open web page",
        "browser_act" to "Click, type or scroll on a web page",
        "browser_close" to "Close the browser session",
        "browser_find" to "Find something on the web page",
        "browser_wait" to "Wait for the page to load something",
        "browser_screenshot" to "Take a picture of the web page",
        "browser_extract" to "Pull a page's list of results out as structured rows",
        "browser_upload" to "Attach a file to a web form, such as a resume",
        "browser_tabs" to "Hold two web pages open at once, to compare them",
        "browser_handoff" to "Show the page to the user for a login or a code",
        "validate_action" to "Validate a planned action",
        "connect_device" to "Device connection (reserved)",
        "list_app_deeplinks" to "Discover an app's direct-open links",
        "resolve_deeplink" to "Check where a link would lead",
        "read_notifications" to "Read your notifications (banking/auth filtered out)",
        "get_media_sessions" to "See what's playing",
        "resolve_contact" to "Find a contact's number on-device",
        "find_files" to "Search your files and media",
        "tap" to "Tap an element on screen",
        "press_home" to "Go to the home screen",
        "press_back" to "Press back",
        "press_enter" to "Press enter",
        "volume_up" to "Raise volume",
        "volume_down" to "Lower volume",
        "mute" to "Mute audio",
        "open_recent_apps" to "Open the recents screen",
        "swipe" to "Swipe on screen",
        "scroll_to" to "Scroll to a position",
        "double_tap" to "Double-tap an element",
        "long_press" to "Long-press an element",
        "scroll_up" to "Scroll up",
        "scroll_down" to "Scroll down",
        "scroll_left" to "Scroll left",
        "scroll_right" to "Scroll right",
        "type_text" to "Type text (screened before sending)",
        "launch_app" to "Open an app",
        "open_deeplink" to "Open a screen directly via link",
        "notification_action" to "Act on a notification (e.g. direct reply)",
        "dismiss_notification" to "Dismiss a notification",
        "media_control" to "Play, pause or skip media",
        "system_intent" to "Alarms, timers, calls, SMS, calendar, share, navigation",
        "open_file" to "Open a file in its app",
    )

    /**
     * Every classified tool, name-sorted, joined with its description. Derived from
     * [McpToolScopes.toolScopeMap] so a newly-classified tool appears automatically
     * (description falls back to the tool name until copy is added above).
     */
    val entries: List<McpToolInfo> =
        McpToolScopes.toolScopeMap.entries
            .map { (name, scope) -> McpToolInfo(name, scope, descriptions[name] ?: name) }
            .sortedBy { it.name }

    /** Test seam: which classified tools still lack human copy (fall back to name). */
    fun toolsMissingDescription(): Set<String> =
        McpToolScopes.toolScopeMap.keys - descriptions.keys
}
