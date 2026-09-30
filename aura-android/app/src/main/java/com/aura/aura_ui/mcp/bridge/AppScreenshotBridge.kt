package com.aura.aura_ui.mcp.bridge

import android.content.Context
import com.aura.aura_ui.accessibility.AccessibilityCapturePolicy
import com.aura.aura_ui.accessibility.AccessibilityScreenshotSource
import com.aura.aura_ui.accessibility.AccessibilityShot
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.accessibility.CaptureRoute
import com.aura.aura_ui.accessibility.ScreenCapturePermissionActivity
import com.aura.mcp.bridge.CaptureResult
import com.aura.mcp.bridge.ScreenshotBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import com.aura.aura_ui.services.startActivityAsAura

/**
 * Adapter for [ScreenshotBridge] — wraps the existing
 * [com.aura.aura_ui.accessibility.ScreenCaptureManager] (callback-based) in a
 * suspending API the MCP tools can call cleanly.
 *
 * Permission flow matches the existing path used by the Python MCP server:
 * the bridge launches [ScreenCapturePermissionActivity], which hosts the
 * system consent dialog. The user grants permission once and the MediaProjection
 * token is stored on the accessibility service for the lifetime of the process.
 */
class AppScreenshotBridge(
    private val appContext: Context,
) : ScreenshotBridge {

    private val service: AuraAccessibilityService?
        get() = AuraAccessibilityService.instance

    override fun isReady(): Boolean {
        service ?: return false
        // There is NOTHING to be ready for — no projection, no token, no consent. A live
        // service on a supported OS is the whole precondition.
        return AccessibilityCapturePolicy.route(mediaProjectionAvailable = false) != CaptureRoute.NONE
    }

    /**
     * No-op by design. `takeScreenshot()` needs no permission, so there is nothing to ask for —
     * and the MediaProjection consent dialog must never be raised again (the mirror is disabled).
     * Returning the readiness state keeps callers that gate on this working unchanged.
     */
    override fun requestPermission(): Boolean = isReady()

    override suspend fun captureBase64Png(): CaptureResult = captureMutex.withLock {
        val svc = service ?: return@withLock CaptureResult.Error("Accessibility service not running")
        when (val shot = svc.captureScreenshot()) {
            is AccessibilityShot.Ok -> CaptureResult.Success(
                base64Png = shot.base64Jpeg,
                widthPx = shot.widthPx,
                heightPx = shot.heightPx,
            )
            // Deliberately Error, never PermissionRequired: no permission would fix a
            // FLAG_SECURE refusal, and PermissionRequired would send the caller into a consent
            // loop it can never satisfy.
            is AccessibilityShot.Failed -> CaptureResult.Error(shot.message)
        }
    }

    private suspend fun captureOnce(): CaptureResult {
        val svc = service ?: return CaptureResult.Error("Accessibility service not running")
        if (!isReady()) return CaptureResult.PermissionRequired

        // The capture callback may fire many ms later (or not at all if
        // MediaProjection gets revoked mid-flight). A 7-second timeout matches
        // the existing ScreenCaptureManager.CAPTURE_TIMEOUT_MS of 6 s with a
        // small grace window.
        //
        // NOTE: do NOT add a "rebuild the VirtualDisplay and retry" recovery
        // here. Android forbids calling MediaProjection#createVirtualDisplay
        // more than once on the same projection instance (and forbids reusing
        // the stored resultData to mint a new one) — doing so throws
        // SecurityException and tears down the whole projection. A genuinely
        // revoked projection is already handled cleanly: the MediaProjection
        // onStop callback nulls the resources, isReady() goes false, and this
        // method returns PermissionRequired so the caller re-requests consent.
        val result = withTimeoutOrNull(7_000L) {
            suspendCancellableCoroutine<CaptureResult> { cont ->
                runCatching {
                    svc.screenCaptureManager.captureScreenWithAnalysis(force = true) { data ->
                        val payload = if (data.screenshot.isNotEmpty()) {
                            CaptureResult.Success(
                                base64Png = data.screenshot,
                                widthPx = data.screenWidth,
                                heightPx = data.screenHeight,
                            )
                        } else {
                            CaptureResult.Error(data.error ?: "Capture returned empty image")
                        }
                        if (cont.isActive) cont.resume(payload)
                    }
                }.onFailure { t ->
                    if (cont.isActive) cont.resume(CaptureResult.Error(t.message ?: "Dispatch failed"))
                }
            }
        }
        return result ?: CaptureResult.Error("Capture timed out after 7s")
    }

    private companion object {
        /**
         * Process-wide capture lock. [com.aura.aura_ui.accessibility.ScreenCaptureManager]
         * has a single pending-callback slot for one in-flight capture, so two
         * concurrent callers — e.g. perceive_screen's own capture racing the
         * McpSessionLogger screenshot it triggers on the same tool call — would
         * overwrite each other's callback, delivering one frame and orphaning
         * the other until its 7 s timeout. Serializing here makes each capture
         * run alone so every caller gets its own frame. Captures are fast
         * (~100 ms) on a healthy projection, so the added latency is small.
         */
        private val captureMutex = Mutex()
    }
}
