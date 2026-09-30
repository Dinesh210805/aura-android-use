package com.aura.aura_ui.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import com.aura.aura_ui.compat.Oem
import com.aura.aura_ui.compat.OemCompat

/** What actually happened when we tried to put a window on the screen. */
enum class OverlayVerdict(val isSuccess: Boolean, val userMessage: String) {
    /** The window drew. Nothing to do. */
    VISIBLE(true, ""),

    /** The standard Android "Display over other apps" toggle is off. */
    PERMISSION_MISSING(
        false,
        "AURA needs \"Display over other apps\" to show the assistant bubble.",
    ),

    /** WindowManager.addView() threw — bad token, bad type, or no window service. */
    ADD_FAILED(
        false,
        "AURA couldn't attach the assistant bubble to the screen. Restarting the app usually fixes this.",
    ),

    /**
     * Permission granted, add succeeded, nothing rendered — and this device ships
     * a vendor overlay gate the Android API can't see.
     */
    OEM_SUPPRESSED(
        false,
        "Your phone is blocking AURA's bubble with an extra manufacturer permission. " +
            "One more toggle and it will appear.",
    ),

    /** Same signature on a device with no known vendor gate. Genuinely unexplained. */
    NOT_DRAWN(
        false,
        "AURA's bubble was created but never appeared on screen. Try toggling " +
            "\"Display over other apps\" off and on again.",
    ),

    /**
     * The window was removed before it ever drew — the user dismissed it, or the
     * service tore it down. Not a fault, and counted as success so no recovery
     * prompt fires. Without this the probe accuses a perfectly healthy phone
     * whenever someone swipes the bubble away quickly.
     */
    DISMISSED(true, ""),
}

/**
 * Turns four observable facts into a diagnosis.
 *
 * The whole reason this exists: on MIUI/HyperOS (and ColorOS, Funtouch, EMUI)
 * `Settings.canDrawOverlays()` can report `true` while a *second*, private
 * vendor permission — "Display pop-up windows while running in background" —
 * is denied. In that state `WindowManager.addView()` returns normally and the
 * window is simply never composited. There is no exception, no log, no API to
 * query. The only detectable difference between "working" and "silently
 * suppressed" is whether the view was ever drawn.
 *
 * Kept pure so every branch is unit-tested; see [OverlayVisibilityProbe] for
 * the framework side that produces `drewWithinTimeout`.
 */
object OverlaySuppressionPolicy {

    /**
     * @param stillAttached whether the view was still in the window hierarchy when
     *  the deadline expired. A detached view never had the chance to draw, which
     *  is categorically different from being allowed to and not doing so.
     */
    fun verdict(
        canDrawOverlays: Boolean,
        addViewThrew: Boolean,
        drewWithinTimeout: Boolean,
        oem: Oem,
        stillAttached: Boolean = true,
    ): OverlayVerdict = when {
        // Ordering matters: the AOSP permission is the cheapest, most likely and
        // most fixable cause, so it is reported even if other signals also failed.
        !canDrawOverlays -> OverlayVerdict.PERMISSION_MISSING
        addViewThrew -> OverlayVerdict.ADD_FAILED
        drewWithinTimeout -> OverlayVerdict.VISIBLE
        // Removed before it could draw — the user dismissed it or the service
        // tore it down. Blaming the manufacturer here would be a false alarm.
        !stillAttached -> OverlayVerdict.DISMISSED
        OemCompat.hasHiddenOverlayGate(oem) -> OverlayVerdict.OEM_SUPPRESSED
        else -> OverlayVerdict.NOT_DRAWN
    }
}

/**
 * Watches a freshly-added overlay view and reports whether it ever reached the
 * screen.
 *
 * Uses `ViewTreeObserver.OnDrawListener` rather than attachment state on purpose:
 * a vendor-suppressed window is still *attached* (`isAttachedToWindow == true`)
 * and still reports a window token — it is simply never composited. The first
 * `onDraw` is the earliest signal that pixels actually happened.
 */
class OverlayVisibilityProbe(
    private val context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** Set when the caller tears the overlay down; suppresses a pending verdict. */
    @Volatile
    private var cancelled = false

    companion object {
        private const val TAG = "OverlayProbe"

        /**
         * Generous on purpose: a cold overlay on a low-end device can take a
         * beat to inflate and draw, and a false "your phone is blocking us"
         * warning is worse than a slow one.
         */
        const val DEFAULT_TIMEOUT_MS = 2_000L

        /**
         * If the first deadline passes while the view is still attached and
         * undrawn, wait this much longer before concluding anything. A budget
         * device doing a cold Compose inflate under memory pressure can
         * legitimately exceed the first deadline — and those are exactly the
         * cheap Xiaomi handsets this probe targets, so a premature verdict
         * would fire the vendor warning precisely where it is least warranted.
         */
        const val GRACE_TIMEOUT_MS = 4_000L
    }

    /**
     * Stop watching. Call when the overlay is deliberately taken down so a
     * pending deadline cannot report a healthy phone as broken.
     */
    fun cancel() {
        cancelled = true
    }

    /**
     * Begin watching [view]. [onVerdict] fires exactly once, on the main thread.
     *
     * @param addViewThrew whether the preceding `addView()` call raised.
     */
    fun watch(
        view: View?,
        addViewThrew: Boolean,
        onVerdict: (OverlayVerdict) -> Unit,
    ) {
        val canDraw = Settings.canDrawOverlays(context)

        // Nothing to watch — decide immediately.
        if (view == null || addViewThrew || !canDraw) {
            val verdict = OverlaySuppressionPolicy.verdict(
                canDrawOverlays = canDraw,
                addViewThrew = addViewThrew,
                drewWithinTimeout = false,
                oem = OemCompat.current,
            )
            onVerdict(verdict)
            return
        }

        var drew = false
        var settled = false

        val observer = view.viewTreeObserver
        val drawListener = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                drew = true
                // Listeners cannot be removed from inside onDraw().
                view.post { runCatching { view.viewTreeObserver.removeOnDrawListener(this) } }
            }
        }
        observer.addOnDrawListener(drawListener)

        fun settle(waitedMs: Long) {
            if (settled) return
            settled = true
            runCatching { view.viewTreeObserver.removeOnDrawListener(drawListener) }

            val verdict = OverlaySuppressionPolicy.verdict(
                canDrawOverlays = Settings.canDrawOverlays(context),
                addViewThrew = false,
                drewWithinTimeout = drew,
                oem = OemCompat.current,
                stillAttached = view.isAttachedToWindow,
            )
            // Always log the verdict, not just failures: "no warning" is not
            // evidence the probe ran, and a user's bug report needs to show
            // which of the two indistinguishable states their device was in.
            val detail = "${android.os.Build.MANUFACTURER}/${android.os.Build.BRAND} " +
                "oem=${OemCompat.current} attached=${view.isAttachedToWindow} " +
                "size=${view.width}x${view.height} drew=$drew waited=${waitedMs}ms"
            if (verdict.isSuccess) {
                Log.i(TAG, "Overlay verdict=$verdict ($detail)")
            } else {
                Log.w(TAG, "Overlay never drew within ${waitedMs}ms → $verdict ($detail)")
            }
            onVerdict(verdict)
        }

        handler.postDelayed({
            if (settled) return@postDelayed
            // Torn down deliberately: say nothing at all.
            if (cancelled) {
                settled = true
                runCatching { view.viewTreeObserver.removeOnDrawListener(drawListener) }
                Log.i(TAG, "Overlay probe cancelled before verdict")
                return@postDelayed
            }
            // Drew, or already gone → decide now; both are cheap and certain.
            if (drew || !view.isAttachedToWindow) {
                settle(timeoutMs)
                return@postDelayed
            }
            // Still attached and still blank. That is the suppression signature,
            // but it is also what a slow cold draw looks like — give it one more
            // window before accusing the manufacturer.
            handler.postDelayed({
                if (cancelled) {
                    settled = true
                    runCatching { view.viewTreeObserver.removeOnDrawListener(drawListener) }
                    Log.i(TAG, "Overlay probe cancelled during grace period")
                    return@postDelayed
                }
                settle(timeoutMs + GRACE_TIMEOUT_MS)
            }, GRACE_TIMEOUT_MS)
        }, timeoutMs)
    }
}
