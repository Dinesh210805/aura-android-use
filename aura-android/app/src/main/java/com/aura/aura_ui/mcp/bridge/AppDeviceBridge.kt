package com.aura.aura_ui.mcp.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.DisplayMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import android.view.WindowManager
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.utils.AgentLogger
import com.aura.mcp.bridge.AppCandidate
import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.bridge.DeviceStatus
import com.aura.mcp.bridge.ScrollDirection
import com.aura.mcp.bridge.VolumeDirection
import com.aura.aura_ui.services.startActivityAsAura

/**
 * Adapter for the [DeviceBridge] port — wires `:mcp-server` to the real
 * `AuraAccessibilityService` running in `:app`.
 *
 * Volume operations are routed through [AuraAccessibilityService.executeSystemAction]
 * (the same path the existing Python MCP server uses for `volume_*` / `mute`),
 * not the private `adjustVolume(Int)` — this keeps both surfaces consistent and
 * avoids touching the service's encapsulation.
 *
 * Gesture-style methods (tap/home/back/swipe/etc) fire-and-forget at this layer:
 * a `true` return means dispatch was issued, not that the OS has finished the
 * gesture. Real per-call ACK plumbing matching the Python `command_id` /
 * `gesture_ack` flow is deferred to a later phase.
 */
class AppDeviceBridge(
    private val appContext: Context,
) : DeviceBridge {

    private val service: AuraAccessibilityService?
        get() = AuraAccessibilityService.instance

    // ── Phase 2 ─────────────────────────────────────────────────────────────

    // Gestures now AWAIT the OS outcome (svc.*Await / *Result) instead of the old
    // fire-and-forget `runCatching { svc.perform*() }.isSuccess`, which reported a
    // tap as "success" the moment dispatch was *issued* — even when the gesture
    // failed or landed nowhere. The agent (and the session logger) now get the
    // real boolean, so a missed tap surfaces as a failure the agent can recover
    // from. runBlocking is safe here: the bridge runs on a background MCP/agent
    // coroutine, never the main thread, and a gesture resolves in ~100–500 ms.
    override fun performTap(x: Int, y: Int): Boolean {
        val svc = service ?: return false
        return runCatching { runBlocking { svc.tapAwait(x, y) } }.getOrDefault(false)
    }

    override fun performHome(): Boolean {
        val svc = service ?: return false
        return runCatching { svc.performHomeResult() }.getOrDefault(false)
    }

    override fun performBack(): Boolean {
        val svc = service ?: return false
        return runCatching { svc.performBackResult() }.getOrDefault(false)
    }

    override fun adjustVolume(direction: VolumeDirection): Boolean {
        val svc = service ?: return false
        val action = when (direction) {
            VolumeDirection.UP -> "volume_up"
            VolumeDirection.DOWN -> "volume_down"
            VolumeDirection.MUTE -> "mute"
        }
        return runCatching { svc.executeSystemAction(action) }.isSuccess
    }

    override fun getDeviceStatus(): DeviceStatus {
        val (w, h) = screenSizePx()
        return DeviceStatus(
            accessibilityServiceRunning = AuraAccessibilityService.isServiceRunning(),
            screenWidthPx = w,
            screenHeightPx = h,
            androidApiLevel = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            foregroundPackage = foregroundPackage(),
            foregroundActivity = null,
        )
    }

    /**
     * Package of the app currently on screen, or null when the accessibility
     * service is off / the top window has no readable package. Callers treat
     * null as "unknown" and fall back to pre-foreground behaviour, so this stays
     * safe on devices where accessibility is unavailable.
     */
    internal fun foregroundPackage(): String? {
        val svc = service ?: return null
        return runCatching {
            svc.rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Human-readable label for [packageName]; null if it isn't installed/readable. */
    internal fun appLabelFor(packageName: String): String? = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    // ── Phase 3 ─────────────────────────────────────────────────────────────

    override fun performDoubleTap(x: Int, y: Int): Boolean {
        val svc = service ?: return false
        // 120 ms is the standard Android double-tap window. Await both taps in
        // sequence; success requires both to land.
        return runCatching {
            runBlocking {
                val first = svc.tapAwait(x, y)
                delay(120L)
                val second = svc.tapAwait(x, y)
                first && second
            }
        }.getOrDefault(false)
    }

    override fun performLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        val svc = service ?: return false
        return runCatching {
            runBlocking { svc.longPressAwait(x, y, durationMs.coerceAtLeast(300L)) }
        }.getOrDefault(false)
    }

    override fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val svc = service ?: return false
        return runCatching {
            runBlocking { svc.swipeAwait(x1, y1, x2, y2, durationMs.coerceAtLeast(100L)) }
        }.getOrDefault(false)
    }

    override fun performScroll(direction: ScrollDirection): Boolean {
        val svc = service ?: return false
        val dir = when (direction) {
            ScrollDirection.UP -> "up"
            ScrollDirection.DOWN -> "down"
            ScrollDirection.LEFT -> "left"
            ScrollDirection.RIGHT -> "right"
        }
        return runCatching { runBlocking { svc.scrollAwait(dir) } }.getOrDefault(false)
    }

    override fun performRecents(): Boolean {
        val svc = service ?: return false
        return runCatching { svc.performRecentsResult() }.getOrDefault(false)
    }

    override fun pressEnter(): Boolean {
        val svc = service ?: return false
        return runCatching { svc.performEnterAction() }.getOrDefault(false)
    }

    override fun typeText(text: String): Boolean {
        val svc = service ?: return false
        return runCatching {
            // Accessibility SET_TEXT (fast path, native apps).
            if (svc.performTextInput(text)) return@runCatching true
            // IME channel — apps with no editable accessibility node (React Native / Flutter).
            runBlocking { svc.typeViaIme(text) } == AuraAccessibilityService.ImeTypeOutcome.SUCCESS
        }.getOrDefault(false)
    }

    override fun typeTextAt(text: String, x: Int, y: Int): Boolean {
        val svc = service ?: return false
        return runCatching {
            // Fast path: the target at (x,y) is already an editable node — type into it.
            if (svc.performTextInput(text, x, y)) return@runCatching true

            // Self-heal: no editable node was found at (x,y) and nothing was focused.
            // Common cause — the som_id points at a "search bar" that is really a button
            // opening a dedicated search screen (Rapido, Uber, Play Store, etc.), or at a
            // visual-only (omniparser) detection with no editable node behind it. Tap it to
            // open/focus, let the new screen settle, then type into the field that appears.
            AgentLogger.Auto.i(
                "type_text: no editable field at ($x,$y); tapping to open/focus, then retrying",
            )
            runBlocking { svc.tapAwait(x, y) }
            var typed = false
            for (attempt in 0 until FOCUS_RETRY_ATTEMPTS) {
                Thread.sleep(FOCUS_SETTLE_MS)
                if (svc.performTextInput(text)) {
                    typed = true
                    break
                }
            }
            // Still no editable node (React Native / Flutter / canvas field) → IME channel.
            // The tap above focused the field, so the AURA keyboard can commit into it.
            if (!typed) {
                AgentLogger.Auto.i("type_text: no editable node after tap; trying AURA keyboard (IME)")
                typed = runBlocking { svc.typeViaIme(text) } == AuraAccessibilityService.ImeTypeOutcome.SUCCESS
            }
            typed
        }.getOrDefault(false)
    }

    override fun isTextInjectionKeyboardEnabled(): Boolean =
        service?.isAuraImeEnabled() ?: false

    override fun restoreKeyboard(): Boolean {
        val svc = service ?: return false
        return runCatching {
            svc.restoreKeyboard()
            true
        }.getOrDefault(false)
    }

    override fun launchApp(packageName: String): Boolean {
        val svc = service
        if (svc != null) {
            // svc.launchApp returns false when the package has no launch intent —
            // .getOrDefault(false) propagates that (NOT .isSuccess, which only checks
            // that no exception was thrown and so reports a failed launch as success).
            return runCatching { svc.launchApp(packageName) }.getOrDefault(false)
        }
        // Fallback: launch via app context even if accessibility service is off.
        // This is the path that makes launch_app the first useful tool a user
        // can issue right after install, before they've enabled accessibility.
        return runCatching {
            val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivityAsAura(intent)
            true
        }.getOrDefault(false)
    }

    override fun lookupApp(query: String): AppLookupResult {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            return AppLookupResult(
                found = false,
                packageName = "",
                appName = query,
                candidates = emptyList(),
                error = "Empty query",
            )
        }

        val pm = appContext.packageManager
        val flags = PackageManager.GET_META_DATA
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags)
        }

        // Tiers in AppNameMatch.score — label first, then the label + package words ("Google Maps"
        // → com.google.android.apps.maps), then a bare package substring.
        val scored = installed.mapNotNull { info ->
            val label = pm.getApplicationLabel(info).toString()
            val score = AppNameMatch.score(needle, label, info.packageName)
            if (score == 0) null else Triple(score, label, info.packageName)
        }.sortedByDescending { it.first }

        if (scored.isEmpty()) {
            return AppLookupResult(
                found = false,
                packageName = "",
                appName = query,
                candidates = emptyList(),
                error = "No installed app matches '$query'",
            )
        }

        val best = scored.first()
        return AppLookupResult(
            found = true,
            packageName = best.third,
            appName = best.second,
            candidates = scored.take(10).map { AppCandidate(packageName = it.third, appName = it.second) },
            error = null,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Screen size as reported to the agent over MCP. Must be the SAME number the
     * gesture executor clamps against and the screenshot is captured at, or the
     * model reasons in one coordinate space while taps land in another.
     */
    private fun screenSizePx(): Pair<Int, Int> =
        com.aura.aura_ui.accessibility.ScreenGeometry.realSizePx(appContext)

    private companion object {
        // After tap-to-focus in typeTextAt, poll for the newly-focused editable field.
        const val FOCUS_RETRY_ATTEMPTS = 3
        const val FOCUS_SETTLE_MS = 250L
    }
}
