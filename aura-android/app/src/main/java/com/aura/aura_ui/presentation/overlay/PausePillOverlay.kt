package com.aura.aura_ui.presentation.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import com.aura.aura_ui.R
import com.aura.aura_ui.services.ControlLockStore
import com.aura.aura_ui.services.OverlayEvasion
import com.aura.aura_ui.services.PausePillBehavior

/**
 * The run controls: a Pause button that lives at the bottom centre for the whole run, and
 * the Cancel button that joins it once paused.
 *
 * ### What this replaced (2026-09-07)
 *
 * This was the *Pause pill* — a draggable thing that appeared only **after** the human had
 * already interrupted the agent, and whose whole job was to hand control back. It was the
 * only way out of a lock the user had usually triggered by accident.
 *
 * It is now a control rather than an escape hatch. It is on screen for the whole run, so
 * the user can pause *before* reaching for the phone; and because it is present the whole
 * time, one fixed place beats remembering where it was last dragged to.
 * [PausePillBehavior]'s KDoc carries the full reversal.
 *
 * ### Two things that are load-bearing, not styling
 *
 * **`FLAG_NOT_TOUCHABLE` is absent here on purpose** — unlike the status strip these are
 * buttons and must receive touches. The cost is that they sit over whatever is underneath,
 * which is why they are small, bottom-centred, and taken off screen during capture.
 *
 * **Cancel is not shown while running.** Stopping and discarding are separate decisions,
 * and a Cancel button under a moving run is one thumb-brush from throwing away the work.
 *
 * Plain Android views rather than Compose: this is added straight to the [WindowManager]
 * from a service, with no Activity and therefore no lifecycle/savedState owner for a
 * `ComposeView` to attach to.
 */
class PausePillOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var primary: ImageView? = null
    private var cancel: ImageView? = null

    /** Remembered so [render] is a no-op when nothing actually changed. */
    private var shown: PausePillBehavior.Controls = PausePillBehavior.Controls.HIDDEN

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()

    /**
     * Bring the controls to the state the two facts imply.
     *
     * A single entry point on purpose: the run flag and the pause flag arrive from
     * different places (`ControlLockStore.localRunning` and `.pausedByHuman`), and letting
     * each drive its own show/hide is how you end up offering Resume for a run that has
     * already ended.
     */
    fun render(localRunActive: Boolean, paused: Boolean) {
        val want = PausePillBehavior.controlsFor(localRunActive, paused)
        if (want == shown) return
        shown = want

        when (want) {
            PausePillBehavior.Controls.HIDDEN -> hide()
            PausePillBehavior.Controls.PAUSE_ONLY -> {
                show()
                primary?.setImageResource(R.drawable.ic_pause)
                primary?.contentDescription = "Pause"
                cancel?.visibility = View.GONE
            }
            PausePillBehavior.Controls.RESUME_AND_CANCEL -> {
                show()
                primary?.setImageResource(R.drawable.ic_play)
                primary?.contentDescription = "Resume"
                cancel?.visibility = View.VISIBLE
            }
        }
    }

    /** Take the controls off the glass so they stay out of the agent's own screenshots. */
    fun setHiddenForCapture(hidden: Boolean) {
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /**
     * Where these buttons actually are, in real display pixels — null until they are on
     * screen AND laid out.
     *
     * Read from [View.getLocationOnScreen] rather than computed from the layout params,
     * because the gravity + margin the window was requested with is not necessarily where
     * the window manager put it (insets, cutouts, and OEM shells all move it). A computed
     * guess that is subtly wrong would let a tap through onto a button that is really
     * there — silently swallowing it, which is the whole failure this feeds.
     *
     * Null while `INVISIBLE`: a hidden window cannot intercept anything, so reporting no
     * bounds is both true and the answer that avoids a pointless second hide.
     */
    fun boundsOnScreen(): OverlayEvasion.Bounds? {
        val view = root ?: return null
        if (view.visibility != View.VISIBLE) return null
        if (view.width == 0 || view.height == 0) return null
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return OverlayEvasion.Bounds(
            left = at[0],
            top = at[1],
            right = at[0] + view.width,
            bottom = at[1] + view.height,
        )
    }

    fun hide() {
        shown = PausePillBehavior.Controls.HIDDEN
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        primary = null
        cancel = null
    }

    private fun show() {
        if (root != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "Cannot show run controls — overlay permission missing")
            return
        }

        val view = buildView()
        runCatching { windowManager.addView(view, layoutParams()) }
            .onFailure {
                Log.e(TAG, "Run controls rejected by WindowManager: ${it.message}")
                shown = PausePillBehavior.Controls.HIDDEN
                return
            }
        root = view
    }

    // ── view ───────────────────────────────────────────────────────────

    private fun buildView(): LinearLayout {
        val pauseBtn = circleButton(R.drawable.ic_pause, "Pause", INK) {
            // One tap, one meaning. The old two-tap dance existed because the control could
            // shrink to an ambiguous dot; nothing shrinks now.
            val lock = ControlLockStore.shared
            if (lock.isPausedByHuman) lock.resume() else lock.pause()
        }
        primary = pauseBtn

        val cancelBtn = circleButton(R.drawable.ic_close, "Cancel run", BLOOD) {
            context.startService(
                Intent(context, com.aura.aura_ui.overlay.AuraOverlayService::class.java)
                    .apply { action = ACTION_CANCEL_TASK },
            )
        }.apply { visibility = View.GONE }
        cancel = cancelBtn

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pauseBtn)
            addView(cancelBtn)
        }
    }

    /**
     * A [BUTTON_DP] circle — the same size as the overlay's mic button, which is the
     * control this one sits beside in the user's head.
     */
    private fun circleButton(
        icon: Int,
        label: String,
        fill: Int,
        onClick: () -> Unit,
    ): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = label
        setPadding(GLYPH_INSET_DP.dp(), GLYPH_INSET_DP.dp(), GLYPH_INSET_DP.dp(), GLYPH_INSET_DP.dp())
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(1.dp(), Color.parseColor("#33FFFFFF"))
        }
        layoutParams = LinearLayout.LayoutParams(BUTTON_DP.dp(), BUTTON_DP.dp()).apply {
            marginStart = GAP_DP.dp()
            marginEnd = GAP_DP.dp()
        }
        setOnClickListener { onClick() }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE so the controls never steal the keyboard from whatever the user
            // is doing — which, while paused, is the entire reason the agent stepped aside.
            // No FLAG_LAYOUT_NO_LIMITS: it would let the window extend past the navigation
            // bar, which is precisely where these buttons must not be.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = PausePillBehavior.bottomMarginPx(
                navBarInsetPx = navBarInsetPx(),
                minimumPx = MIN_BOTTOM_MARGIN_DP.dp(),
            )
        }
    }

    /**
     * The navigation bar's height, or 0 when it cannot be read.
     *
     * Read from the platform resource rather than from window insets because this window is
     * still being built — it has no `rootWindowInsets` to consult until it is attached. The
     * floor in [PausePillBehavior.bottomMarginPx] is what makes a 0 here harmless.
     */
    private fun navBarInsetPx(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private companion object {
        const val TAG = "RunControlsOverlay"

        /** Matches the overlay's mic button, so the two read as the same family of control. */
        const val BUTTON_DP = 64
        const val GLYPH_INSET_DP = 18
        const val GAP_DP = 8
        const val MIN_BOTTOM_MARGIN_DP = 48

        const val ACTION_CANCEL_TASK = "com.aura.CANCEL_TASK"

        val INK: Int = Color.parseColor("#E6101010")

        /** Mono's blood red — reserved for destructive actions, which cancelling a run is. */
        val BLOOD: Int = Color.parseColor("#E6A31621")
    }
}
