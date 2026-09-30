package com.aura.mcp.bridge

/**
 * Port for capturing screen images via Android's MediaProjection API.
 *
 * Capture is fundamentally async (Android delivers frames through an
 * `ImageReader` callback), so [captureBase64Png] is a `suspend` function.
 * Implementations are responsible for marshalling between the bridge's
 * coroutine context and the underlying callback API.
 *
 * Captures depend on the user having granted screen-capture permission via
 * the standard system dialog. If permission has not been granted,
 * [isReady] returns false and [captureBase64Png] returns
 * [CaptureResult.PermissionRequired]. Callers should then issue
 * [requestPermission] which launches the consent dialog asynchronously.
 */
interface ScreenshotBridge {
    /** True if MediaProjection has been initialised and capture can proceed. */
    fun isReady(): Boolean

    /**
     * Launch the system consent dialog. Returns true if the request was
     * dispatched (not whether the user accepted — that's discovered later
     * via [isReady]). No-op if [isReady] is already true.
     */
    fun requestPermission(): Boolean

    /** Capture a single frame and return it as a base64-encoded PNG. */
    suspend fun captureBase64Png(): CaptureResult
}

sealed class CaptureResult {
    data class Success(
        val base64Png: String,
        val widthPx: Int,
        val heightPx: Int,
    ) : CaptureResult()

    data class Error(val message: String) : CaptureResult()

    object PermissionRequired : CaptureResult()
}
