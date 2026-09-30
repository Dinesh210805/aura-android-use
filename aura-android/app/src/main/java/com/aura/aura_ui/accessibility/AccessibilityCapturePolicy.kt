package com.aura.aura_ui.accessibility

import android.os.Build

/** Which mechanism serves a screenshot request. */
enum class CaptureRoute {
    /** `AccessibilityService.takeScreenshot()` — API 30+. No consent, no indicator, nothing held. */
    ACCESSIBILITY,

    /**
     * The legacy MediaProjection + VirtualDisplay mirror.
     *
     * **DISABLED — [AccessibilityCapturePolicy.route] never returns this.** Kept, along with
     * [ScreenCaptureManager], because MediaProjection is the correct primitive for a future
     * screen-*mirroring* feature (streaming the phone to a desktop viewer), where the capture is
     * continuous, user-initiated, and the screen-cast indicator is honest. It is the wrong
     * primitive for perception, which is discrete sampling.
     */
    MEDIA_PROJECTION,

    /** No capture possible; the caller degrades to UI-tree-only perception. */
    NONE,
}

/**
 * Pure decision logic for screen capture, split out from the Android glue so it can be tested
 * on the JVM (`AccessibilityService.takeScreenshot` cannot run there).
 *
 * ## Why the accessibility route is preferred (2026-08-12)
 *
 * [ScreenCaptureManager] holds a live `MediaProjection` + `VirtualDisplay` + `ImageReader` from
 * the moment the user grants screen capture until the accessibility service unbinds — its
 * `cleanup()` has exactly one caller, `AuraAccessibilityService.onUnbind()`. So the display is
 * mirrored continuously, the screen-cast privacy indicator stays lit while AURA is idle, and a
 * full-screen surface composites for nothing.
 *
 * Scoping that projection to a single task does NOT fix it: on `targetSdk 36` the MediaProjection
 * consent token is single-use (API 34+), so re-acquiring means a system consent dialog before
 * every automation task, which ends hands-free operation.
 *
 * `takeScreenshot()` needs no projection, no consent dialog and lights no indicator. The service
 * already declares `canTakeScreenshot="true"` in `res/xml/aura_accessibility_service.xml`.
 *
 * Measured on a OnePlus CPH2661 (ColorOS, API 36): 15 ms per capture, output 1240x2772 — an
 * exact match for `ScreenGeometry.realSizePx`, so som_id boxes need no rescaling.
 */
object AccessibilityCapturePolicy {

    /**
     * **The MediaProjection kill switch (2026-08-12).**
     *
     * [ScreenCaptureManager.initializeMediaProjection] and
     * [ScreenCaptureManager.captureScreenWithAnalysis] both refuse while this is `false`, so no
     * call site can start the mirror — including UI paths that still offer a "grant screen
     * capture" button and the legacy `request_screen_capture_permission` MCP command. Gating at
     * the source rather than at ~15 call sites is what makes "disabled" provable instead of
     * aspirational: a new caller added tomorrow is disabled by default.
     *
     * The code below the gate is intact and reachable the moment this flips to `true` — kept for
     * the future screen-*mirroring* feature, where MediaProjection is the correct primitive.
     */
    const val MIRROR_ENABLED = false

    /**
     * The framework's own throttle: `AccessibilityService`'s
     * `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`. Requests closer together than
     * this fail with [ERROR_INTERVAL_TIME_SHORT]. Confirmed by device measurement (a 250 ms gap
     * fails, 500 ms succeeds).
     *
     * This is generous for AURA: the perceive→act→verify loop puts an LLM round-trip between
     * captures. It only bites on retry paths that re-perceive immediately after a failed action.
     */
    const val MIN_INTERVAL_MS = 333L

    // Framework error codes. 5 and 6 exist in the platform but are not exposed as constants at
    // our compileSdk, so they are pinned numerically.
    const val ERROR_INTERNAL = 1
    const val ERROR_NO_ACCESSIBILITY_ACCESS = 2
    const val ERROR_INTERVAL_TIME_SHORT = 3
    const val ERROR_INVALID_DISPLAY = 4
    const val ERROR_INVALID_WINDOW = 5
    const val ERROR_SECURE_WINDOW = 6

    /**
     * Picks the capture mechanism.
     *
     * Only two outcomes are reachable: [CaptureRoute.ACCESSIBILITY] on API 30+, and
     * [CaptureRoute.NONE] below it. [CaptureRoute.MEDIA_PROJECTION] is deliberately unreachable —
     * no call site may start the mirror (2026-08-12). Pre-API-30 devices degrade to
     * UI-tree-only perception rather than falling back to it.
     *
     * [mediaProjectionAvailable] is retained in the signature on purpose: it documents that an
     * available projection is *known about and still declined*, and it keeps the seam ready for
     * the future mirroring feature. Re-enabling means changing this function and the test that
     * pins it shut — a deliberate act, not an accident.
     */
    @Suppress("UNUSED_PARAMETER")
    fun route(
        sdkInt: Int = Build.VERSION.SDK_INT,
        mediaProjectionAvailable: Boolean,
    ): CaptureRoute = when {
        sdkInt >= Build.VERSION_CODES.R -> CaptureRoute.ACCESSIBILITY
        else -> CaptureRoute.NONE
    }

    /**
     * How long to wait before issuing the next capture so it does not trip the throttle.
     *
     * Clamped to `[0, MIN_INTERVAL_MS]` at BOTH ends. The upper bound is the load-bearing one:
     * if the clock jumps backwards, `elapsed` goes negative and `MIN_INTERVAL_MS - elapsed`
     * grows without limit — a clock that moved back an hour would block the agent for an hour.
     * Waiting one full interval is always enough to clear the throttle, so nothing above that
     * is ever useful.
     */
    fun waitBeforeNextMs(lastCaptureAtMs: Long?, nowMs: Long): Long {
        if (lastCaptureAtMs == null) return 0L
        val elapsed = nowMs - lastCaptureAtMs
        return (MIN_INTERVAL_MS - elapsed).coerceIn(0L, MIN_INTERVAL_MS)
    }

    /**
     * Whether waiting and trying again could succeed. Only the throttle is transient — a secure
     * window refuses for as long as that screen is up, and retrying burns the agent's turn budget
     * against a wall.
     */
    fun isRetryable(errorCode: Int): Boolean = errorCode == ERROR_INTERVAL_TIME_SHORT

    /**
     * Human-readable failure, surfaced to the model.
     *
     * [ERROR_SECURE_WINDOW] matters most: the old MediaProjection path returned a BLACK IMAGE
     * with no error on protected screens, so the model would try to reason about a blank
     * rectangle. Saying "protected" out loud lets it explain itself instead of hallucinating.
     */
    fun describeError(errorCode: Int): String = when (errorCode) {
        ERROR_INTERNAL -> "Screenshot failed inside the system (internal error)"
        ERROR_NO_ACCESSIBILITY_ACCESS -> "Screenshot refused: accessibility service lacks screenshot capability"
        ERROR_INTERVAL_TIME_SHORT -> "Screenshots requested too quickly (min ${MIN_INTERVAL_MS}ms apart)"
        ERROR_INVALID_DISPLAY -> "Screenshot failed: invalid display"
        ERROR_INVALID_WINDOW -> "Screenshot failed: invalid window"
        ERROR_SECURE_WINDOW ->
            "This screen is protected by the app and cannot be captured (FLAG_SECURE). " +
                "Read it from the UI tree instead, or ask the user what it shows."
        else -> "Screenshot failed with unknown error code $errorCode"
    }
}
