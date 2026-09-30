package com.aura.mcp.bridge

/**
 * Port (Hexagonal Architecture term) into the host Android app.
 *
 * The `:mcp-server` module declares **what** it needs from the device side.
 * The `:app` module provides the **how** by binding this interface to a real
 * `AuraAccessibilityService`-backed implementation. This is the only doorway
 * between the two modules — `:mcp-server` must never reference any concrete
 * Android service class directly.
 *
 * Gesture-style methods are fire-and-forget at the OS level: a `true` return
 * means the call dispatched without throwing, not that the OS has finished the
 * gesture. Real per-call acknowledgement (matching the existing Phase-7 ACK
 * system on the Python side) is deferred to a later phase.
 *
 * Implementations must be **thread-safe** — MCP tool dispatch happens on Ktor's
 * IO dispatcher, but the underlying accessibility APIs require main-thread
 * dispatch. Implementations marshal as needed.
 */
interface DeviceBridge {

    // ── Phase 2 surface ─────────────────────────────────────────────────────

    fun performTap(x: Int, y: Int): Boolean
    fun performHome(): Boolean
    fun performBack(): Boolean
    fun adjustVolume(direction: VolumeDirection): Boolean
    fun getDeviceStatus(): DeviceStatus

    // ── Phase 3 surface ─────────────────────────────────────────────────────

    /** Tap twice in quick succession at the same point. */
    fun performDoubleTap(x: Int, y: Int): Boolean

    /** Press-and-hold at a point for [durationMs] milliseconds. */
    fun performLongPress(x: Int, y: Int, durationMs: Long): Boolean

    /** Swipe from (x1, y1) to (x2, y2) over [durationMs] milliseconds. */
    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    /** Scroll in [direction] (UP/DOWN/LEFT/RIGHT) — a 50% screen-distance swipe. */
    fun performScroll(direction: ScrollDirection): Boolean

    /** Inject the Recent Apps system action. */
    fun performRecents(): Boolean

    /** Inject the Enter key on the currently focused editor. */
    fun pressEnter(): Boolean

    /** Type [text] into the currently focused editable field. */
    fun typeText(text: String): Boolean

    /**
     * Type [text] into the editable field located at full-resolution ([x], [y]) — the
     * center of a perceived element. Targeting the exact field (rather than "whatever is
     * focused") fixes typing into the wrong box on multi-field forms and avoids opening
     * the soft keyboard. Defaulted to [typeText] so existing test doubles keep compiling;
     * the real app binding overrides it.
     */
    fun typeTextAt(text: String, x: Int, y: Int): Boolean = typeText(text)

    /**
     * Whether AURA's own keyboard (the IME used to type into apps that expose no editable
     * accessibility node — React Native / Flutter / canvas) is enabled in system settings.
     * When false and a type_text fails, the tool tells the agent/user to enable it once.
     * Defaulted to true so test doubles and non-Android bridges are unaffected.
     */
    fun isTextInjectionKeyboardEnabled(): Boolean = true

    /**
     * Restore normal soft-keyboard behaviour after automation suppressed it
     * (typing hides the IME so it can't obscure perception). Called by
     * `end_session` so a finished task hands the keyboard back immediately;
     * the accessibility service's inactivity watchdog is the backstop for
     * runs that never end cleanly.
     */
    fun restoreKeyboard(): Boolean

    /** Launch the app with the given Android [packageName]. */
    fun launchApp(packageName: String): Boolean

    /** Resolve a human-readable [query] to an [AppLookupResult]. */
    fun lookupApp(query: String): AppLookupResult
}

enum class VolumeDirection { UP, DOWN, MUTE }

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Coarse-grained device state surfaced through the `get_device_status` MCP tool.
 *
 * Kept intentionally small — richer fields (battery, network state, foreground
 * app) can be added later without breaking existing callers because the wire
 * format is JSON.
 */
data class DeviceStatus(
    val accessibilityServiceRunning: Boolean,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val androidApiLevel: Int,
    val deviceModel: String,
    // Foreground app the accessibility service currently sees. Null when the
    // service is off or the top window has no readable package — every caller
    // treats null as "unknown" and degrades to pre-foreground behaviour.
    val foregroundPackage: String? = null,
    val foregroundActivity: String? = null,
)

/**
 * Result of resolving a human-readable app name to an installed package.
 *
 * Mirrors the shape of the Python `lookup_app` tool so callers don't need to
 * relearn the schema. `candidates` is ordered by match-confidence (best first).
 */
data class AppLookupResult(
    val found: Boolean,
    val packageName: String,
    val appName: String,
    val candidates: List<AppCandidate>,
    val error: String?,
)

data class AppCandidate(
    val packageName: String,
    val appName: String,
)
