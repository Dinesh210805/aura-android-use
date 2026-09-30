package com.aura.mcp.server

/**
 * GP5+GP6 — foreground-package gate classification. The financial/auth
 * blocklist protects ENTRY (`launch_app`/`open_deeplink`), but not observation
 * or continuation: an agent could coordinate-tap a banking icon from the home
 * screen (GP6), and `perceive_screen` OCRs whatever is foreground — account
 * numbers and 2FA codes included — into prompts, third-party providers, and
 * persisted traces (GP5, P4). This object decides, per tool, whether a call
 * must be denied while a [SensitivePolicy]-blocked package is foreground.
 *
 * Enforced in [scopedTool] via [ServerSinks.gate]; the foreground package is
 * read from the accessibility tree only for gated tools (zero cost otherwise).
 *
 * Fail-open on UNKNOWN foreground (blank / failed snapshot): the entry gate is
 * the primary defense, and screen-off ("act without screen") flows plus
 * launcher screens must keep working. Fail-closed on a policy MATCH, like the
 * rest of [SensitivePolicy].
 *
 * Deliberately ungated:
 *  - Escape hatches (`press_home`, `press_back`, `open_recent_apps`) — the
 *    agent must always be able to LEAVE a blocked app.
 *  - Entry points (`launch_app`, `open_deeplink`) — already screened
 *    pre-dispatch + on the resolved package (GP2/E3).
 *  - Tools that act outside the foreground app (audio, media sessions,
 *    notifications, system_intent, open_file).
 */
internal object ForegroundGuard {

    enum class Kind { GESTURE, PERCEPTION }

    /** WRITE tools that act INSIDE the foreground app — denied while blocked. */
    val GATED_GESTURES: Set<String> = setOf(
        "tap", "double_tap", "long_press", "swipe",
        "scroll_to", "scroll_up", "scroll_down", "scroll_left", "scroll_right",
        "type_text", "press_enter",
    )

    /** READ tools that OBSERVE the foreground screen — denied while blocked. */
    val GATED_PERCEPTION: Set<String> = setOf(
        "perceive_screen", "get_screenshot", "read_screen",
        "verify_action", "wait_for", "watch_device_events",
    )

    /** WRITE tools deliberately NOT gated — reasons in the class KDoc; coverage-tested. */
    val UNGATED_WRITE_TOOLS: Set<String> = setOf(
        "press_home", "press_back", "open_recent_apps",
        "launch_app", "open_deeplink",
        "volume_up", "volume_down", "mute", "media_control",
        "notification_action", "dismiss_notification", "system_intent", "open_file",
        // Browser — act on a browser surface, never inside the foreground app.
        // TRUE ONLY WHILE `session:"mine"` IS UNIMPLEMENTED. Slice 1 ships the
        // scratch engine only (AppBrowserBridge reports MINE unavailable), so no
        // browser_* call can currently reach the user's real Chrome. When the
        // `mine` backend lands, these MUST move to GATED_GESTURES: driving the
        // user's signed-in browser on a banking page is precisely the case this
        // gate exists for, and it would otherwise be the one sensitive surface
        // with no foreground check.
        "browser_open", "browser_act", "browser_close", "browser_find", "browser_wait",
        "browser_upload",
        "browser_tabs",
        "browser_handoff",
    )

    /** READ tools deliberately NOT gated — none can see the foreground screen; coverage-tested. */
    val UNGATED_READ_TOOLS: Set<String> = setOf(
        "echo", "get_device_status", "request_screen_capture_permission",
        "end_session", "lookup_app", "web_search", "validate_action",
        "connect_device", "list_app_deeplinks", "resolve_deeplink",
        "read_notifications", "get_media_sessions", "resolve_contact", "find_files",
        // Reads a web page in a browser session, not the device screen. Same
        // `mine`-mode caveat as the browser WRITE tools above.
        "browser_read", "browser_screenshot", "browser_extract",
        // Static documentation — reads nothing from the screen at all.
        "get_usage_guide",
    )

    fun gatedKind(toolName: String): Kind? = when (toolName) {
        in GATED_GESTURES -> Kind.GESTURE
        in GATED_PERCEPTION -> Kind.PERCEPTION
        else -> null
    }

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(
            val category: SensitivePolicy.Category,
            val kind: Kind,
            val message: String,
        ) : Verdict
    }

    fun evaluate(toolName: String, foregroundPackage: String?): Verdict {
        val kind = gatedKind(toolName) ?: return Verdict.Allow
        if (foregroundPackage.isNullOrBlank()) return Verdict.Allow
        val decision = SensitivePolicy.screenPackage(foregroundPackage)
        if (decision !is SensitivePolicy.Decision.Block) return Verdict.Allow
        val message = when (kind) {
            Kind.GESTURE ->
                "Blocked for your security: a banking, payment, or authenticator " +
                    "app is in the foreground — I can't act inside it. Ask the user " +
                    "to finish this step themselves. You may use press_back or " +
                    "press_home to leave the app."
            Kind.PERCEPTION ->
                "Blocked for your security: a banking, payment, or authenticator " +
                    "app is in the foreground — I can't read its screen. Ask the " +
                    "user to finish this step themselves. You may use press_back " +
                    "or press_home to leave the app."
        }
        return Verdict.Block(decision.category, kind, message)
    }
}
