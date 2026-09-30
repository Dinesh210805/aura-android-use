package com.aura.mcp.server

import com.aura.mcp.bridge.McpScope

/**
 * Single source of truth: which capability scope each registered MCP tool
 * requires. The auth/scope guard installed by [scopedToolWrapper] consults
 * this map at dispatch time and rejects any call whose principal lacks the
 * scope.
 *
 * Tools default to [McpScope.WRITE] (the safer default) if not listed —
 * adding a new tool without remembering to classify it fails closed rather
 * than open.
 *
 * Classification rules:
 *  - **READ** — tool only inspects state or queries external services.
 *    Cannot move pixels, change settings, install/launch anything, or
 *    affect what the user sees on the screen.
 *  - **WRITE** — tool can drive the device, launch apps, send keystrokes,
 *    change volume, or otherwise mutate device state.
 *
 * Notes on the trickier classifications:
 *  - `request_screen_capture_permission` is **READ** because it only opens
 *    the system consent dialog; the user still has to tap "Allow". The
 *    capture itself happens in `get_screenshot` (also READ — inspection).
 *  - `web_search` is **READ** because no device state changes; the worst
 *    case is burning a Tavily API credit.
 *  - `validate_action` and `connect_device` are READ — Phase 3 stubs that
 *    don't touch hardware.
 */
// Public (was internal): the on-device agent UI in :app reads this to detect an
// automation run — the first WRITE-scoped tool means "driving the device", which
// triggers minimize-to-pill. Reusing this single source of truth avoids a parallel
// tool list in :app that would drift from the policy classification.
object McpToolScopes {

    val toolScopeMap: Map<String, McpScope> = mapOf(
        // ── READ ───────────────────────────────────────────────
        "echo" to McpScope.READ,
        "get_usage_guide" to McpScope.READ,
        "get_device_status" to McpScope.READ,
        "get_screenshot" to McpScope.READ,
        "request_screen_capture_permission" to McpScope.READ,
        "read_screen" to McpScope.READ,
        "watch_device_events" to McpScope.READ,
        // Phase 9 — single perception façade (replaces legacy
        // perceive_screen/get_annotated_screenshot/omniparser_detect).
        "perceive_screen" to McpScope.READ,
        // Phase 9 — flow-control trio.
        "verify_action" to McpScope.READ,
        "wait_for" to McpScope.READ,
        // Phase 10B — agent-callable session boundary marker.
        "end_session" to McpScope.READ,
        "lookup_app" to McpScope.READ,
        "web_search" to McpScope.READ,
        "validate_action" to McpScope.READ,
        "connect_device" to McpScope.READ,
        // Deep links — discovery is passive inspection.
        "list_app_deeplinks" to McpScope.READ,
        "resolve_deeplink" to McpScope.READ,
        // Assistant plane — reading the status bar / media sessions inspects
        // state only (sensitive-package filtering happens inside the tool).
        "read_notifications" to McpScope.READ,
        "get_media_sessions" to McpScope.READ,
        // Contacts — on-device name→number resolution; inspection only. The
        // number must still never leave the tool result (PathStepSanitizer
        // classifies it read-only so it can't enter learnings).
        "resolve_contact" to McpScope.READ,
        // Files — searching the media index is inspection only.
        "find_files" to McpScope.READ,
        // Browser — re-reading an already-open page mutates nothing.
        "browser_read" to McpScope.READ,
        "browser_screenshot" to McpScope.READ,
        "browser_extract" to McpScope.READ,
        "browser_upload" to McpScope.WRITE,
        // WRITE: opening and closing tabs mutates the browser surface. `list` alone is
        // read-only, but the scope is per-tool, and classifying by the strongest action
        // a tool can take is the only safe direction.
        "browser_tabs" to McpScope.WRITE,
        // WRITE: it puts a window over the user's screen. Polling with check=true is
        // read-only in spirit, but a tool is classified by the strongest thing it does.
        "browser_handoff" to McpScope.WRITE,

        // ── WRITE ──────────────────────────────────────────────
        "tap" to McpScope.WRITE,
        "press_home" to McpScope.WRITE,
        "press_back" to McpScope.WRITE,
        "press_enter" to McpScope.WRITE,
        "volume_up" to McpScope.WRITE,
        "volume_down" to McpScope.WRITE,
        "mute" to McpScope.WRITE,
        "open_recent_apps" to McpScope.WRITE,
        "swipe" to McpScope.WRITE,
        "scroll_to" to McpScope.WRITE,
        "double_tap" to McpScope.WRITE,
        "long_press" to McpScope.WRITE,
        "scroll_up" to McpScope.WRITE,
        "scroll_down" to McpScope.WRITE,
        "scroll_left" to McpScope.WRITE,
        "scroll_right" to McpScope.WRITE,
        "type_text" to McpScope.WRITE,
        // E4 — taps a real element (resolved from the live tree at dispatch time).
        "launch_app" to McpScope.WRITE,
        // Phase 9 — composes perceive_screen + scroll until target found.
        // Deep link — firing an Intent mutates device state (navigates the UI,
        // can land on payment/auth/compose screens).
        "open_deeplink" to McpScope.WRITE,
        // Assistant plane — these act on the world: a fired notification action
        // sends a real reply, media commands change playback, system_intent
        // launches real activities (dialer, composer, share sheet, maps).
        "notification_action" to McpScope.WRITE,
        "dismiss_notification" to McpScope.WRITE,
        "media_control" to McpScope.WRITE,
        "system_intent" to McpScope.WRITE,
        // Files — opening a file launches its viewer app (screen changes).
        "open_file" to McpScope.WRITE,
        // Browser — WRITE even though `session:"scratch"` touches nothing the user
        // can see. Scope is static per tool name, but the same three tools also
        // accept `session:"mine"`, which drives the user's REAL signed-in browser:
        // navigating, clicking and submitting on pages holding their accounts. The
        // classification has to cover the most dangerous reachable behaviour, so it
        // is pinned to the `mine` case. Cost of this choice: a READ-only principal
        // cannot use the scratch browser either. If that becomes a real limitation,
        // the fix is a separate READ-only fetch tool, not a downgrade here.
        "browser_open" to McpScope.WRITE,
        "browser_act" to McpScope.WRITE,
        "browser_close" to McpScope.WRITE,
        // find/wait scroll and poll the page, so they can change what is rendered.
        "browser_find" to McpScope.WRITE,
        "browser_wait" to McpScope.WRITE,
    )

    /** Fail-closed default for any tool we forgot to classify. */
    fun requiredScopeFor(toolName: String): McpScope =
        toolScopeMap[toolName] ?: McpScope.WRITE
}
