package com.aura.aura_ui.mcp.bridge.browser

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.graphics.drawable.shapes.RoundRectShape
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.aura.aura_ui.R

/**
 * Spec 2026-08-06 — *the browser plane becomes a browser*.
 *
 * The Mono design language, hand-ported for plain `View`s.
 *
 * The browser overlay is added straight to the `WindowManager`, not composed, so it
 * cannot reference `ui/theme/Mono.kt`'s Compose `Color` values. Rather than let three
 * files each re-declare `0xFF0A0A0A` and drift apart — which is how the old window ended
 * up with a hardcoded palette in its companion object — every shared token and every
 * repeated widget shape lives here once.
 */
internal object ChromeStyle {

    // ── palette (mirrors ui/theme/Mono.kt) ─────────────────────────────

    /** Mono.Ink — black cards, pills, chrome. */
    const val INK = 0xFF0A0A0A.toInt()

    /** One step up from ink: input fields and pressed surfaces, so they read as inset. */
    const val INK_RAISED = 0xFF1A1A19.toInt()

    /** Mono.OutlineOnInk — the hairline. */
    const val OUTLINE = 0xFF2E2E2B.toInt()

    /** Mono.TextOnInk. */
    const val TEXT = 0xFFF5F5F2.toInt()

    /** Secondary text: present but not competing with the URL. */
    const val TEXT_DIM = 0xFF9A9A95.toInt()

    /**
     * Mono.Blood — "look here now" ONLY. In this window that means exactly three things:
     * the handoff prompt, the close affordance, and an insecure-connection warning. Never
     * decorative; see the Mono doctrine in CLAUDE.md.
     */
    const val BLOOD = 0xFFA31621.toInt()

    // ── metrics ────────────────────────────────────────────────────────

    const val TAB_ROW_DP = 44
    const val TOOLBAR_DP = 52
    const val PROGRESS_DP = 2
    const val ICON_BUTTON_DP = 40
    const val ICON_DP = 20

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

    // ── widgets ────────────────────────────────────────────────────────

    /**
     * A tappable icon.
     *
     * Borderless ripple rather than a flat press-alpha: these sit on a black bar where a
     * 20 % alpha change is invisible, and a control that gives no feedback reads as
     * broken long before the user works out it did fire.
     *
     * [enabled] dims rather than hides. Back and forward that vanish when unavailable make
     * the toolbar reflow under the user's thumb between taps.
     */
    fun iconButton(
        context: Context,
        @DrawableRes icon: Int,
        description: String,
        tint: Int = TEXT,
        onClick: () -> Unit,
    ): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(tint)
        contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = context.dp((ICON_BUTTON_DP - ICON_DP) / 2)
        setPadding(pad, pad, pad, pad)
        background = borderlessRipple(context)
        isClickable = true
        // NOT focusable. This window takes real focus (the user types in it), so when the
        // address bar calls clearFocus() the focus falls to the next focusable view — the
        // "+" button — and its ripple paints a permanent focused state. Observed on
        // device: "+" sat lit like a pressed button for the rest of the session. Nothing
        // here is reached by D-pad, and accessibility services target clickable views
        // regardless, so focusability buys nothing and costs that.
        isFocusable = false
        setOnClickListener { onClick() }
    }

    fun ImageView.setEnabledDimmed(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.32f
    }

    fun borderlessRipple(context: Context): RippleDrawable =
        RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(48, 255, 255, 255)),
            null,
            ShapeDrawable(OvalShape()).apply { intrinsicWidth = context.dp(ICON_BUTTON_DP) },
        )

    /** A filled rounded surface with a hairline — the shape used for the URL field and chips. */
    fun surface(context: Context, fill: Int, stroke: Int?, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dpf(radiusDp)
            setColor(fill)
            stroke?.let { setStroke(context.dp(1), it) }
        }

    /** A ripple clipped to [surface]'s rounded shape, so a chip's feedback matches its edges. */
    fun surfaceRipple(context: Context, fill: Int, stroke: Int?, radiusDp: Float): RippleDrawable {
        val r = context.dpf(radiusDp)
        val radii = FloatArray(8) { r }
        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
            surface(context, fill, stroke, radiusDp),
            ShapeDrawable(RoundRectShape(radii, null, null)),
        )
    }

    /**
     * The AURA mark in solid white.
     *
     * The **1.28× scale is load-bearing**, not a nicety: `ic_launcher_foreground` carries
     * transparent adaptive-icon safe-zone padding, so drawn at its natural size the mark
     * reads as a small logo floating in dead space. This mirrors `AuraLogoMark.drawLogo`,
     * which applies exactly the same correction on the Compose side.
     *
     * White rather than the brand gradient by explicit user request (2026-08-06), and it
     * suits the surface anyway — this is an identity mark on black chrome, not a hero.
     */
    fun auraMark(context: Context, sizeDp: Int): ImageView = ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher_foreground)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.FIT_CENTER
        scaleX = 1.28f
        scaleY = 1.28f
        contentDescription = "AURA"
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
    }

    fun label(
        context: Context,
        text: String,
        sizeSp: Float = 14f,
        color: Int = TEXT,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = sizeSp
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
    }

    fun row(context: Context, heightDp: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(heightDp),
        )
    }

    fun spacer(context: Context, widthDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(widthDp), 1)
    }
}
