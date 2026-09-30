package com.aura.aura_ui.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision logic behind the screenshot-path swap (2026-08-12).
 *
 * The framework call itself (`AccessibilityService.takeScreenshot`) cannot run on the JVM, so
 * the parts that CAN carry a bug — which mechanism to use, how long to wait between captures,
 * and what a failure code means — are isolated here and tested directly.
 */
class AccessibilityCapturePolicyTest {

    // ── route(): which mechanism serves a capture ──────────────────────────────

    @Test
    fun `API 30 and above uses the accessibility route`() {
        assertEquals(
            CaptureRoute.ACCESSIBILITY,
            AccessibilityCapturePolicy.route(sdkInt = 30, mediaProjectionAvailable = false),
        )
    }

    @Test
    fun `accessibility route wins even when MediaProjection is available`() {
        // The whole point of the swap: never light the screen-cast indicator when we don't
        // have to. An available projection must NOT be preferred just because it exists.
        assertEquals(
            CaptureRoute.ACCESSIBILITY,
            AccessibilityCapturePolicy.route(sdkInt = 36, mediaProjectionAvailable = true),
        )
    }

    @Test
    fun `below API 30 has no route even when MediaProjection is available`() {
        // The MediaProjection path is DISABLED, not deleted (2026-08-12 decision): no call site
        // may spin up the mirror. An available projection must not resurrect it — pre-API-30
        // devices degrade to UI-tree-only perception instead.
        assertEquals(
            CaptureRoute.NONE,
            AccessibilityCapturePolicy.route(sdkInt = 29, mediaProjectionAvailable = true),
        )
    }

    @Test
    fun `below API 30 with no MediaProjection has no route`() {
        // minSdk is 26, so this is reachable. Caller degrades to UI-tree-only.
        assertEquals(
            CaptureRoute.NONE,
            AccessibilityCapturePolicy.route(sdkInt = 26, mediaProjectionAvailable = false),
        )
    }

    @Test
    fun `the MediaProjection mirror is disabled`() {
        // The single kill switch. ScreenCaptureManager consults this before minting a
        // projection, so no call site anywhere can start the mirror — including UI paths that
        // still offer a "grant screen capture" button. Flipping this to true is the ONLY way
        // back on, which is what makes the future mirroring feature a deliberate act.
        assertFalse(AccessibilityCapturePolicy.MIRROR_ENABLED)
    }

    @Test
    fun `no input combination can select the MediaProjection route`() {
        // Belt-and-braces: the enum constant survives for the future screen-mirroring feature,
        // but nothing may route to it today. If someone re-enables it, this test says so.
        val everyCombination = listOf(26, 29, 30, 33, 36).flatMap { sdk ->
            listOf(true, false).map { mp -> AccessibilityCapturePolicy.route(sdk, mp) }
        }
        assertFalse(
            "MediaProjection route is disabled but was selected: $everyCombination",
            everyCombination.contains(CaptureRoute.MEDIA_PROJECTION),
        )
    }

    // ── waitBeforeNextMs(): the 333ms framework throttle ───────────────────────

    @Test
    fun `first ever capture waits for nothing`() {
        assertEquals(0L, AccessibilityCapturePolicy.waitBeforeNextMs(lastCaptureAtMs = null, nowMs = 1_000))
    }

    @Test
    fun `a capture 100ms ago waits out the remaining interval`() {
        // 333 - 100 = 233. Measured on device: 250ms gap fails, 500ms succeeds — consistent
        // with the framework's ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333.
        assertEquals(233L, AccessibilityCapturePolicy.waitBeforeNextMs(lastCaptureAtMs = 900, nowMs = 1_000))
    }

    @Test
    fun `a capture longer ago than the interval waits for nothing`() {
        assertEquals(0L, AccessibilityCapturePolicy.waitBeforeNextMs(lastCaptureAtMs = 500, nowMs = 1_000))
    }

    @Test
    fun `a clock that jumped backwards waits at most one interval`() {
        // A backwards jump makes elapsed negative, and `interval - negative` GROWS. Without a
        // ceiling, a clock that moved back an hour would block the agent for an hour. The wait
        // must stay inside [0, MIN_INTERVAL_MS] no matter how nonsensical the timestamps are.
        val wait = AccessibilityCapturePolicy.waitBeforeNextMs(lastCaptureAtMs = 2_000, nowMs = 1_000)
        assertTrue(
            "wait must stay bounded by one interval, was ${wait}ms",
            wait in 0L..AccessibilityCapturePolicy.MIN_INTERVAL_MS,
        )
    }

    // ── error classification ───────────────────────────────────────────────────

    @Test
    fun `throttle failures are retryable`() {
        assertTrue(AccessibilityCapturePolicy.isRetryable(ERROR_INTERVAL_TIME_SHORT))
    }

    @Test
    fun `a secure window is not retryable`() {
        // FLAG_SECURE screens (banking, password managers) refuse permanently. Retrying
        // would just burn the agent's turn budget against a wall.
        assertFalse(AccessibilityCapturePolicy.isRetryable(ERROR_SECURE_WINDOW))
    }

    @Test
    fun `secure window failure says so in plain words`() {
        // This string reaches the model. It must explain that the screen is protected —
        // the old MediaProjection path returned a BLACK IMAGE with no error at all, and the
        // model would try to reason about a blank rectangle.
        val message = AccessibilityCapturePolicy.describeError(ERROR_SECURE_WINDOW)
        assertTrue("expected a protected-screen explanation, got: $message", message.contains("protected"))
    }

    @Test
    fun `an unknown error code still produces a usable message`() {
        val message = AccessibilityCapturePolicy.describeError(999)
        assertTrue("expected the raw code to survive, got: $message", message.contains("999"))
    }

    private companion object {
        // Framework values (AccessibilityService.ERROR_TAKE_SCREENSHOT_*). 5 and 6 are not
        // exposed as constants at our compileSdk, so they are pinned numerically here.
        const val ERROR_INTERVAL_TIME_SHORT = 3
        const val ERROR_SECURE_WINDOW = 6
    }
}
