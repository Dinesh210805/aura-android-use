package com.aura.aura_ui.debugtools

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import androidx.annotation.RequiresApi
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.accessibility.ScreenGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * SPIKE (2026-08-12, debug-only) — can `AccessibilityService.takeScreenshot()` replace the
 * MediaProjection screenshot pipeline entirely?
 *
 * ## Why this exists
 *
 * [com.aura.aura_ui.accessibility.ScreenCaptureManager] holds a live `MediaProjection` +
 * `VirtualDisplay` + `ImageReader` from the moment the user grants screen capture until the
 * accessibility service unbinds — `cleanup()` has exactly one caller, `AuraAccessibilityService.
 * onUnbind()`. So the display is mirrored continuously, the screen-cast privacy indicator stays
 * lit while AURA is idle, and a full-screen surface is composited for nothing.
 *
 * The obvious fix — release after each capture, re-acquire from the cached consent Intent — does
 * NOT work on `targetSdk 36`: since API 34 the MediaProjection consent token is single-use, so
 * re-acquiring means a system consent dialog before **every** automation task. That kills
 * hands-free operation, which is the product. (`ScreenCaptureManager` already carries the scar:
 * `invalidatePermission()` on a "token exhausted" SecurityException.)
 *
 * `AccessibilityService.takeScreenshot()` (API 30+) needs no MediaProjection, no consent dialog
 * and lights no privacy indicator — and the service ALREADY declares `canTakeScreenshot="true"`
 * in `res/xml/aura_accessibility_service.xml`. The capability is paid for and never used.
 *
 * ## What this spike decides
 *
 * If all five probes pass on real devices, `ScreenCaptureManager`,
 * `ScreenCapturePermissionActivity` and the `screen_capture` permission entry become DELETABLE
 * rather than fixable. If P1 or P4 fail, we fall back to scoping MediaProjection to the run.
 *
 * This measures. It changes no production code path — nothing here is wired into the agent.
 *
 *   adb shell am broadcast \
 *     -n com.aura.aura_ui.feature.debug/com.aura.aura_ui.debugtools.CompanionTestReceiver \
 *     -a com.aura.aura_ui.debug.COMPANION_TEST \
 *     --es mode screenshot
 *
 *   adb exec-out run-as com.aura.aura_ui.feature.debug \
 *     sh -c 'cat files/companion_test/$(ls -t files/companion_test | head -1)'
 */
object AccessibilityScreenshotSpike {

    /** Delays probed for the throttle floor (P3), smallest first. */
    private val THROTTLE_LADDER_MS = listOf(0L, 100L, 250L, 500L, 1_000L, 2_000L)

    /** Matches ScreenCaptureManager.bitmapToBase64 so byte sizes are comparable like-for-like. */
    private const val JPEG_QUALITY = 80

    private val executor = Executors.newSingleThreadExecutor()

    /** One capture attempt: either a decoded bitmap + timing, or the reason it failed. */
    private sealed interface Shot {
        data class Ok(val bitmap: Bitmap, val elapsedMs: Long) : Shot
        data class Err(val code: Int, val elapsedMs: Long) : Shot {
            val name: String get() = errorName(code)
        }
    }

    suspend fun run(): String {
        val service = AuraAccessibilityService.instance
            ?: return "FAIL: accessibility service not running — enable it and retry"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // minSdk is 26, so this branch is real code, not a formality. If the fleet has
            // pre-30 devices, takeScreenshot() cannot be the only path.
            return "SKIP: takeScreenshot() needs API 30+, device is API ${Build.VERSION.SDK_INT} " +
                "— MediaProjection would still be required here"
        }
        return runOnR(service)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun runOnR(service: AccessibilityService): String {
        val out = StringBuilder()
        out.append("device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} || ")

        // ── P1: does it work at all? The OEM question. Everything else is moot if this fails.
        val first = capture(service)
        out.append("P1_BASIC=")
        when (first) {
            is Shot.Err -> {
                out.append("FAIL(${first.name}) — takeScreenshot() unusable on this device")
                return out.toString()
            }
            is Shot.Ok -> out.append("ok ${first.elapsedMs}ms ${first.bitmap.width}x${first.bitmap.height}")
        }

        // ── P4: does it match the GESTURE coordinate space? Run this early — a size mismatch is
        // silently fatal. ScreenCaptureManager's own init comment records that sizing the
        // VirtualDisplay from displayMetrics produced a scaled copy, so every OmniParser box was
        // offset from where a tap actually landed. Same trap applies here.
        val (geomW, geomH) = ScreenGeometry.realSizePx(service)
        val matches = first.bitmap.width == geomW && first.bitmap.height == geomH
        out.append(" || P4_COORD_SPACE=")
        out.append(
            if (matches) {
                "ok (matches ScreenGeometry ${geomW}x$geomH)"
            } else {
                "MISMATCH shot=${first.bitmap.width}x${first.bitmap.height} " +
                    "geometry=${geomW}x$geomH — som_id boxes would be offset; needs scaling before use"
            },
        )

        // ── P2: payload size vs the MediaProjection path, same JPEG settings.
        val b64 = toBase64(first.bitmap)
        out.append(" || P2_PAYLOAD=${b64.length / 1024}KB_base64 (jpeg q$JPEG_QUALITY)")
        first.bitmap.recycle()

        // ── P3: the throttle floor. The perceive->act->verify loop can fire captures close
        // together; we need the real minimum gap, not a guess at the documented constant.
        out.append(" || P3_THROTTLE=")
        out.append(probeThrottle(service))

        // ── P5: secure windows (FLAG_SECURE) cannot be automated from here — banking apps,
        // password managers. Noted so it is not mistaken for "untested = fine".
        out.append(
            " || P5_SECURE_WINDOW=MANUAL (open a FLAG_SECURE screen and re-run; expect " +
                "ERROR_TAKE_SCREENSHOT_SECURE_WINDOW on API 33+, blank/black frame below that)",
        )

        return out.toString()
    }

    /**
     * Walks [THROTTLE_LADDER_MS] and returns the smallest delay at which a back-to-back second
     * capture succeeds. Reported as a measured floor, not a documented one — the constant is not
     * guaranteed stable across OEM builds.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun probeThrottle(service: AccessibilityService): String {
        val notes = mutableListOf<String>()
        for (gap in THROTTLE_LADDER_MS) {
            // Primer establishes "a capture just happened" so the next one is the throttled case.
            when (val primer = capture(service)) {
                is Shot.Ok -> primer.bitmap.recycle()
                is Shot.Err -> {
                    notes.add("${gap}ms:primer_${primer.name}")
                    delay(1_500)
                    continue
                }
            }
            delay(gap)
            when (val second = capture(service)) {
                is Shot.Ok -> {
                    second.bitmap.recycle()
                    notes.add("${gap}ms:ok")
                    return "floor<=${gap}ms (${notes.joinToString(",")})"
                }
                is Shot.Err -> notes.add("${gap}ms:${second.name}")
            }
            // Settle before the next rung so the previous attempt's throttle does not leak in.
            delay(1_500)
        }
        return "NO_FLOOR_FOUND up to ${THROTTLE_LADDER_MS.last()}ms (${notes.joinToString(",")}) " +
            "— captures cannot be issued back-to-back; the agent loop needs explicit backoff"
    }

    /**
     * One `takeScreenshot()` call, adapted to a software [Bitmap].
     *
     * The result arrives as a `HardwareBuffer` + `ColorSpace`, not an `ImageReader` frame — this
     * is the adapter the production pipeline would need. Two things are load-bearing:
     *  - the buffer MUST be closed, or every capture leaks a full-screen graphics allocation;
     *  - the wrapped bitmap is HARDWARE-config, so it is copied to ARGB_8888 before returning.
     *    `compress()` on a hardware bitmap works, but nothing else in the OmniParser path does.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capture(service: AccessibilityService): Shot =
        suspendCancellableCoroutine { cont ->
            val start = System.currentTimeMillis()
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val elapsed = System.currentTimeMillis() - start
                        val buffer = result.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            buffer.close() // non-negotiable: leaks a screen-sized allocation otherwise
                        }
                        cont.resume(
                            if (bitmap == null) {
                                Shot.Err(ERR_DECODE, elapsed)
                            } else {
                                Shot.Ok(bitmap, elapsed)
                            },
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(Shot.Err(errorCode, System.currentTimeMillis() - start))
                    }
                },
            )
        }

    private fun toBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /** Local decode failure — not an AccessibilityService error code, kept out of their range. */
    private const val ERR_DECODE = -1

    /**
     * Codes 5 and 6 are referenced numerically: ERROR_TAKE_SCREENSHOT_INVALID_WINDOW and
     * ERROR_TAKE_SCREENSHOT_SECURE_WINDOW exist in the framework but are not exposed as
     * constants at our compileSdk. 6 is the one that matters — it is what a FLAG_SECURE
     * screen returns (P5), so without this mapping the manual secure-window run would just
     * print "UNKNOWN(6)" and look like a bug rather than the expected, correct refusal.
     */
    private fun errorName(code: Int): String = when (code) {
        ERR_DECODE -> "WRAP_HARDWARE_BUFFER_RETURNED_NULL"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        5 -> "INVALID_WINDOW"
        6 -> "SECURE_WINDOW (FLAG_SECURE screen — expected refusal, not a failure)"
        else -> "UNKNOWN($code)"
    }
}
