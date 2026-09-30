package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import androidx.annotation.RequiresApi
import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** Outcome of one accessibility screenshot. */
sealed interface AccessibilityShot {
    data class Ok(val base64Jpeg: String, val widthPx: Int, val heightPx: Int) : AccessibilityShot
    data class Failed(val message: String, val retryable: Boolean) : AccessibilityShot
}

/**
 * Captures the screen via `AccessibilityService.takeScreenshot()` (API 30+).
 *
 * This replaces the [ScreenCaptureManager] MediaProjection path for perception. See
 * [AccessibilityCapturePolicy] for why, and for the decision logic (which is unit-tested; this
 * class is the thin framework glue that cannot run on the JVM).
 *
 * Unlike the mirror it replaces, nothing here is held between calls: no projection, no
 * VirtualDisplay, no ImageReader, no consent token, no privacy indicator. A capture allocates,
 * decodes and frees.
 */
class AccessibilityScreenshotSource(
    private val service: AccessibilityService,
) {

    /**
     * `elapsedRealtime` of the last SUCCESSFUL capture, used to pre-empt the framework throttle.
     * Monotonic by construction — unlike wall-clock time it cannot jump backwards — though
     * [AccessibilityCapturePolicy.waitBeforeNextMs] clamps defensively regardless.
     */
    private val lastCaptureAt = AtomicLong(NEVER)

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun capture(): AccessibilityShot {
        awaitThrottle()

        return when (val first = captureOnce()) {
            is AccessibilityShot.Ok -> first
            is AccessibilityShot.Failed -> {
                if (!first.retryable) return first
                // The only retryable failure is the throttle, and it means our own bookkeeping
                // was wrong (a capture happened that we did not record — e.g. another service,
                // or the debug spike). One full interval always clears it.
                AgentLogger.Screen.d("Screenshot throttled — waiting one interval and retrying")
                delay(AccessibilityCapturePolicy.MIN_INTERVAL_MS)
                captureOnce()
            }
        }
    }

    private suspend fun awaitThrottle() {
        val last = lastCaptureAt.get().takeIf { it != NEVER }
        val wait = AccessibilityCapturePolicy.waitBeforeNextMs(last, SystemClock.elapsedRealtime())
        if (wait > 0) delay(wait)
    }

    /**
     * One `takeScreenshot()` round-trip.
     *
     * Two details are load-bearing and easy to get wrong:
     *  - the returned `HardwareBuffer` MUST be closed (hence `finally`), or every capture leaks
     *    a screen-sized graphics allocation;
     *  - `Bitmap.wrapHardwareBuffer` yields a HARDWARE-config bitmap. `compress()` tolerates
     *    that, but the rest of the perception path (OmniParser, any pixel access) does not — so
     *    it is copied to ARGB_8888 before use.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureOnce(): AccessibilityShot =
        suspendCancellableCoroutine { cont ->
            runCatching {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val bitmap = result.hardwareBuffer.let { buffer ->
                                try {
                                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    buffer.close()
                                }
                            }
                            if (bitmap == null) {
                                if (cont.isActive) {
                                    cont.resume(
                                        AccessibilityShot.Failed(
                                            "Screenshot decode failed (wrapHardwareBuffer returned null)",
                                            retryable = false,
                                        ),
                                    )
                                }
                                return
                            }
                            lastCaptureAt.set(SystemClock.elapsedRealtime())
                            val shot = AccessibilityShot.Ok(
                                base64Jpeg = toBase64(bitmap),
                                widthPx = bitmap.width,
                                heightPx = bitmap.height,
                            )
                            bitmap.recycle()
                            if (cont.isActive) cont.resume(shot)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) {
                                cont.resume(
                                    AccessibilityShot.Failed(
                                        AccessibilityCapturePolicy.describeError(errorCode),
                                        retryable = AccessibilityCapturePolicy.isRetryable(errorCode),
                                    ),
                                )
                            }
                        }
                    },
                )
            }.onFailure { t ->
                if (cont.isActive) {
                    cont.resume(
                        AccessibilityShot.Failed(
                            "takeScreenshot dispatch failed: ${t.message}",
                            retryable = false,
                        ),
                    )
                }
            }
        }

    /** JPEG q80 to match [ScreenCaptureManager]'s payload exactly — same bytes on the wire. */
    private fun toBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        const val JPEG_QUALITY = 80
        const val NEVER = -1L

        /**
         * Single thread: `takeScreenshot` is throttled to one call per
         * [AccessibilityCapturePolicy.MIN_INTERVAL_MS] anyway, so there is nothing to gain from
         * a pool, and serialising the callbacks keeps the buffer lifetime easy to reason about.
         */
        val executor = Executors.newSingleThreadExecutor()
    }
}
