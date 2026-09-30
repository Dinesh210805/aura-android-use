package com.aura.aura_ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Premium tap ripple overlay — pure white shades, visible on any background.
 *
 * Draws a 4-layer expanding ripple per tap:
 *  1. Outer echo ring  — thin, very translucent, expands slightly beyond the main ring
 *  2. Main ring        — crisp white stroke, primary visual indicator
 *  3. Inner glow       — soft white shimmer that blooms then quickly dissipates
 *  4. Core dot         — bright white snap-dot at the exact touch point, fades fast
 *
 * Each white element has an ultra-subtle dark shadow ring drawn behind it so the
 * effect remains visible even against near-white / fully-white backgrounds.
 * All drawing is hardware-layer compatible (no BlurMaskFilter).
 */
class TapRippleOverlay(context: Context) : View(context) {

    private data class Ripple(
        val x: Float,
        val y: Float,
        val maxRadius: Float,
        var progress: Float = 0f,
        var animator: ValueAnimator? = null
    )

    private val ripples = mutableListOf<Ripple>()
    private val density = context.resources.displayMetrics.density
    // Single reusable Paint — colour/style set per call, no allocation in onDraw
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Trigger a ripple at screen coordinates ([x], [y]).
     * [maxRadiusDp] is the outer ring's final radius in density-independent pixels.
     */
    fun showRipple(x: Float, y: Float, maxRadiusDp: Float = 96f, durationMs: Long = 520) {
        val ripple = Ripple(x, y, maxRadiusDp * density)
        ripples.add(ripple)

        ripple.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                ripple.progress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ripples.remove(ripple)
                    if (ripples.isEmpty()) visibility = GONE
                }
            })
            start()
        }
        visibility = VISIBLE
    }

    /** Convenience for multi-touch swipe paths — fires ripples at each point with a stagger. */
    fun showMultipleRipples(
        points: List<Pair<Float, Float>>,
        maxRadiusDp: Float = 72f,
        durationMs: Long = 440,
        staggerDelayMs: Long = 50
    ) {
        points.forEachIndexed { index, (x, y) ->
            postDelayed({ showRipple(x, y, maxRadiusDp, durationMs) }, index * staggerDelayMs)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (r in ripples.toList()) drawRipple(canvas, r)
    }

    private fun drawRipple(canvas: Canvas, r: Ripple) {
        val p = r.progress
        val cx = r.x
        val cy = r.y
        val maxR = r.maxRadius

        // ── Layer 1: Outer echo ring
        // Expands to 118 % of maxR, quadratic fade-out, very thin stroke.
        val outerR = maxR * 1.18f * p
        val outerAlpha = ((1f - p) * (1f - p) * 130).toInt().coerceIn(0, 255)
        drawWhiteRing(canvas, cx, cy, outerR, outerAlpha, strokePx = density * 1.2f)

        // ── Layer 2: Main ring
        // Holds full brightness until 30 % then smooth quadratic fade.
        val mainR = maxR * p
        val mainFade = if (p < 0.3f) 1f else (1f - (p - 0.3f) / 0.7f)
        val mainAlpha = (mainFade * mainFade * 240).toInt().coerceIn(0, 255)
        val mainStroke = density * 2.8f * (1f - p * 0.25f)  // gently tapers
        drawWhiteRing(canvas, cx, cy, mainR, mainAlpha, strokePx = mainStroke)

        // ── Layer 3: Inner glow shimmer (first 55 % of animation only)
        if (p < 0.55f) {
            paint.style = Paint.Style.FILL
            val gFrac = p / 0.55f
            val glowR = maxR * 0.44f * (1f - gFrac * 0.35f)
            val baseAlpha = (1f - gFrac) * (1f - gFrac) * 155f
            // 5 concentric fills simulate a radial gradient — works with LAYER_TYPE_HARDWARE
            for (i in 0..4) {
                val t = i / 4f               // 0 = inner-most, 1 = outer-most
                val stepR = glowR * (1f - t * 0.78f)
                val stepAlpha = (baseAlpha * (1f - t * 0.85f)).toInt().coerceIn(0, 255)
                paint.color = Color.argb(stepAlpha, 255, 255, 255)
                canvas.drawCircle(cx, cy, stepR.coerceAtLeast(1f), paint)
            }
            paint.style = Paint.Style.STROKE
        }

        // ── Layer 4: Core touch dot — snapping bright dot, fades within first 38 %
        if (p < 0.38f) {
            paint.style = Paint.Style.FILL
            val dotFrac = p / 0.38f
            val dotR = density * 8f * (0.45f + dotFrac * 0.55f)  // scales 45 %→100 %
            val dotAlpha = ((1f - dotFrac) * (1f - dotFrac) * 255).toInt().coerceIn(0, 255)
            // Hairline dark shadow border — ensures visibility on white backgrounds
            paint.color = Color.argb((dotAlpha * 0.14f).toInt(), 0, 0, 0)
            canvas.drawCircle(cx, cy, dotR + density * 1.5f, paint)
            // The bright white dot itself
            paint.color = Color.argb(dotAlpha, 255, 255, 255)
            canvas.drawCircle(cx, cy, dotR, paint)
            paint.style = Paint.Style.STROKE
        }
    }

    /**
     * Draws a white ring preceded by an ultra-subtle dark shadow ring so the effect
     * stays visible on near-white backgrounds while remaining imperceptible on dark ones.
     */
    private fun drawWhiteRing(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Int, strokePx: Float) {
        if (alpha <= 0 || r <= 0f) return
        paint.style = Paint.Style.STROKE
        // Dark shadow ring (13 % darkness) — thicker so it peeks behind the white ring
        paint.strokeWidth = strokePx + density * 2f
        paint.color = Color.argb((alpha * 0.13f).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy, r, paint)
        // White ring on top
        paint.strokeWidth = strokePx
        paint.color = Color.argb(alpha, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
    }

    fun clearAllRipples() {
        ripples.forEach { it.animator?.cancel() }
        ripples.clear()
        visibility = GONE
    }
}
