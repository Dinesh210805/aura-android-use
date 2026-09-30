package com.aura.aura_ui.accessibility

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * The single source of truth for "how big is the screen, in the coordinate space
 * gestures are dispatched in".
 *
 * ## Why this exists
 * The automation pipeline used to read screen size from three different places:
 *
 *  | Consumer               | Old source                  | What it drives                  |
 *  |------------------------|-----------------------------|---------------------------------|
 *  | `UITreeExtractor`      | `currentWindowMetrics`      | coords reported to the agent    |
 *  | `CoordinateResolver`   | `resources.displayMetrics`  | gesture clamping + scroll spans |
 *  | `ScreenCaptureManager` | `resources.displayMetrics`  | VirtualDisplay the CV model sees|
 *
 * `Resources.getDisplayMetrics()` on a Service context is **not** guaranteed to
 * be the full display: from API 30 it can exclude system-decor insets, and the
 * amount excluded differs per OEM and per navigation mode. When it does differ:
 *
 *  - the captured screenshot is a *scaled* copy of the real screen, so every
 *    YOLO / omniparser box lands in a different coordinate space than
 *    `dispatchGesture()` uses — a systematic, silent offset on every CV tap;
 *  - `coerceIn(0, screenHeight)` clamps legitimate bottom-of-screen taps
 *    (nav bar, bottom sheets, keyboard rows) upward onto the wrong element.
 *
 * On a device where the two sources happen to agree, none of this is visible —
 * which is exactly why it survived until a cross-device audit.
 *
 * `AccessibilityNodeInfo.getBoundsInScreen()` and
 * `AccessibilityService.dispatchGesture()` both work in **real display**
 * coordinates, so the real display is the correct — and only — answer.
 */
object ScreenGeometry {

    /**
     * Real display size in pixels, including the status bar and gesture-nav area.
     *
     * `maximumWindowMetrics` is used rather than `currentWindowMetrics` because in
     * split-screen / freeform the latter shrinks to the app's own window, while
     * gestures are still addressed in whole-display coordinates.
     *
     * @return width to height, or (0, 0) if the display cannot be read.
     */
    fun realSizePx(context: Context): Pair<Int, Int> = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return@runCatching 0 to 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            size.x to size.y
        }
    }.getOrDefault(0 to 0)

    /**
     * Density used when creating the screenshot VirtualDisplay.
     *
     * Safe to read from Resources: densityDpi is a property of the display
     * configuration, not of the window area, so it does not suffer the
     * inset problem that makes width/height unreliable.
     */
    fun densityDpi(context: Context): Int =
        context.resources.displayMetrics.densityDpi.takeIf { it > 0 }
            ?: DisplayMetrics.DENSITY_DEFAULT

    /**
     * Extra bottom padding an overlay window must add to its own margin before
     * trusting `Gravity.BOTTOM` to place it somewhere visible.
     *
     * [realSizePx] deliberately reports the *full* physical display so gesture
     * dispatch always agrees with what the accessibility tree sees. But a window
     * anchored with `Gravity.BOTTOM` is positioned by the OEM's own compositor
     * against however much of that bottom edge it has decided to reserve for
     * itself — gesture-nav, and on several Xiaomi/Redmi builds an "Edge light"
     * strip along the bottom bezel. That reservation is invisible to the Android
     * API: `addView` succeeds, the view measures and draws normally (so
     * [com.aura.aura_ui.overlay.OverlayVisibilityProbe] reports it healthy), and
     * the pixels simply never reach the glass — reported on a real Redmi device
     * as "I can hear AURA but the bubble never appears."
     *
     * System-bar + cutout insets are the one thing the platform *does* expose
     * for "how much of the edge is not mine to draw the rest position in", so a
     * window using `Gravity.BOTTOM` should add this on top of its own resting
     * margin. Windows anchored `Gravity.TOP` do not need it — the top-left
     * origin is the one corner every OEM composites consistently, which is
     * exactly why `EdgeGlow` and `PausePillOverlay` never showed this
     * symptom.
     *
     * @return 0 if insets cannot be read (API < 30, or no active window) —
     *   falls back to the old fixed-margin behaviour rather than guessing.
     */
    fun bottomSafeInsetPx(context: Context): Int = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return@runCatching 0
        val insets = wm.currentWindowMetrics.windowInsets
        val mask = android.view.WindowInsets.Type.systemBars() or
            android.view.WindowInsets.Type.displayCutout()
        insets.getInsets(mask).bottom
    }.getOrDefault(0)
}
