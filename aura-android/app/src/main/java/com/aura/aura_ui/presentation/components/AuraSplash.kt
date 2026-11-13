package com.aura.aura_ui.presentation.components

import android.app.Activity
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The launch animation: a line of light draws the AURA mark's outline and its hole, the white glass
 * fills in from it, a sheen crosses the glass, then the word AURA settles in below.
 *
 * - Contract: [showLaunchSplash] adds a full-screen view over the activity's decor view, plays once
 *   at [SPEED] times the design timeline, fades out and removes itself. A tap skips it. It plays
 *   only for a launcher start ([shouldPlayLaunchSplash]) and not when system animations are off.
 * - Why: `Theme.Aura.Splash` shows a blank black field, so this view is the first thing to draw the
 *   mark. It sits on the decor view so the activity can `setContent` underneath without removing it.
 *   The glow uses `BlurMaskFilter`, which hardware layers ignore below API 28: older phones get the
 *   same motion without the soft glow.
 * - Change together: [MARK_PATH] is the mark in `res/mipmap-*` `ic_launcher_foreground.png`;
 *   `res/values/themes.xml` `windowSplashScreenAnimatedIcon` must stay blank.
 */
fun ComponentActivity.showLaunchSplash() {
    val root = window.decorView as ViewGroup
    val view = ComposeView(this)
    view.setViewTreeLifecycleOwner(this)
    view.setViewTreeSavedStateRegistryOwner(this)
    view.setContent { AuraSplash(onFinished = { root.removeView(view) }) }
    root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

/** True for a plain launcher start with system animations on; every other way into the app skips the splash. */
fun Activity.shouldPlayLaunchSplash(): Boolean {
    val launcher = intent?.action == Intent.ACTION_MAIN && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
    val animationsOn = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    return launcher && animationsOn
}

/** Playback speed against the design timeline in [splashFrame] (1.0 = the design's own pace). */
private const val SPEED = 0.7f
/** Design time at which the last beat (the word landing) is done. */
internal const val SPLASH_END_S = 3.05f
private const val FADE_MS = 350
/** Mark width as a share of the screen's short side. */
private const val MARK_FILL = 0.34f

@Composable
private fun AuraSplash(onFinished: () -> Unit) {
    val painter = remember { SplashPainter() }
    var t by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(FADE_MS),
        label = "splashFade",
        finishedListener = { if (leaving) onFinished() },
    )
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < SPLASH_END_S && !leaving) {
            withFrameNanos { now -> t = (now - start) / 1e9f * SPEED }
        }
        leaving = true
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { leaving = true } }
            .semantics { contentDescription = "AURA" },
    ) {
        val frame = splashFrame(t)
        ComposeCanvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, frame) }
        }
        val markW = min(maxWidth.value, maxHeight.value) * MARK_FILL
        val spacing = 0.9f - 0.48f * frame.word
        Text(
            text = "AURA",
            color = Color.White.copy(alpha = 0.88f * frame.word),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = spacing.em,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // letterSpacing trails the last letter, so nudge right by half of it to stay centred
                .offset(x = (18f * spacing / 2f).dp, y = (maxHeight.value * 0.44f + markW * 0.66f).dp),
        )
    }
}

/** Everything the painter needs for one moment of the splash. All values are 0..1 unless noted. */
internal data class SplashFrame(
    val draw: Float,       // how much of the outline the light line has drawn
    val drawIn: Float,     // same for the hole's edge
    val fill: Float,       // glass opacity
    val scale: Float,      // mark scale, settles to 1
    val haloAlpha: Float,
    val haloBlur: Float,   // in mark units
    val traceAlpha: Float,
    val sparkAlpha: Float,
    val sheenPos: Float,   // band centre along the sweep, runs -0.25..1.25
    val sheenAlpha: Float,
    val word: Float,
)

/** The splash at design time [t] seconds (before [SPEED] is applied). Pure, so it can be tested. */
internal fun splashFrame(t: Float): SplashFrame {
    val draw = inOutCubic(seg(t, 0.2f, 1.45f))
    val drawIn = inOutCubic(seg(t, 0.4f, 1.45f)) // the hole starts a beat later and lands with the outline
    val fill = outCubic(seg(t, 1.2f, 1.95f))
    val flash = bump(seg(t, 1.2f, 2.9f), 0.12f)
    return SplashFrame(
        draw = draw,
        drawIn = drawIn,
        fill = fill,
        scale = 0.955f + 0.045f * outExpo(seg(t, 1.15f, 2.5f)),
        haloAlpha = 0.2f * fill + 0.85f * flash,
        haloBlur = 10f + 10f * flash,
        traceAlpha = (1f - fill) * 0.95f,
        sparkAlpha = if (t < 0.2f || t >= 1.6f) 0f else 1f - seg(t, 1.4f, 1.6f),
        sheenPos = -0.25f + 1.5f * inOutCubic(seg(t, 1.7f, 2.75f)),
        sheenAlpha = 0.95f * (1f - seg(t, 2.55f, 2.8f)),
        word = outExpo(seg(t, 1.75f, SPLASH_END_S)),
    )
}

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun outCubic(x: Float) = 1f - (1f - x).pow(3)
private fun inOutCubic(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
private fun outExpo(x: Float) = if (x >= 1f) 1f else 1f - 2f.pow(-10f * x)
/** Fast rise to 1 at [peak], long fall back to 0. */
private fun bump(x: Float, peak: Float) = when {
    x <= 0f || x >= 1f -> 0f
    x < peak -> outCubic(x / peak)
    else -> (1f - (x - peak) / (1f - peak)).pow(2)
}

/** The AURA mark in a 100-unit box: outline then hole, traced from the launcher art. */
private const val MARK_PATH =
    "M47.13 7.62C45.00 8.03 42.75 8.93 40.78 10.32C38.81 11.72 36.71 13.99 35.31 16.00C33.92 18.00 33.25 20.23 32.41 22.34C31.56 24.46 31.04 26.56 30.25 28.69C29.45 30.82 28.58 32.98 27.61 35.11C26.64 37.24 25.59 39.34 24.44 41.46C23.29 43.57 22.05 45.69 20.72 47.81C19.39 49.92 17.93 52.03 16.47 54.15C15.00 56.28 13.32 58.44 11.94 60.57C10.57 62.70 9.20 64.80 8.23 66.92C7.26 69.03 6.45 71.15 6.14 73.27C5.82 75.38 5.91 77.50 6.34 79.61C6.77 81.73 7.29 84.09 8.70 85.96C10.11 87.83 12.71 89.76 14.78 90.83C16.85 91.90 19.01 92.21 21.13 92.38C23.24 92.55 25.36 92.31 27.48 91.84C29.59 91.37 31.70 90.38 33.83 89.54C35.95 88.71 38.11 87.54 40.24 86.84C42.37 86.14 44.47 85.60 46.59 85.36C48.71 85.11 50.82 85.10 52.94 85.36C55.05 85.61 57.17 86.26 59.29 86.91C61.40 87.56 63.51 88.53 65.63 89.27C67.76 90.02 69.92 90.85 72.05 91.37C74.18 91.88 76.28 92.39 78.40 92.38C80.52 92.37 82.67 92.27 84.75 91.30C86.83 90.33 89.36 88.43 90.89 86.57C92.42 84.71 93.41 82.28 93.93 80.16C94.45 78.03 94.32 75.92 94.00 73.81C93.68 71.69 92.94 69.57 92.04 67.46C91.14 65.34 89.91 63.23 88.60 61.11C87.28 58.99 85.60 56.89 84.14 54.76C82.68 52.63 81.16 50.47 79.82 48.35C78.48 46.22 77.26 44.11 76.10 42.00C74.94 39.88 73.83 37.76 72.86 35.65C71.89 33.53 71.09 31.43 70.29 29.30C69.50 27.17 68.91 25.01 68.07 22.88C67.22 20.76 66.59 18.60 65.23 16.54C63.87 14.48 61.84 11.97 59.89 10.52C57.95 9.08 55.67 8.38 53.55 7.89C51.42 7.41 49.26 7.22 47.13 7.62Z" +
        "M50.30 29.77C51.62 29.53 52.90 29.84 54.22 30.31C55.54 30.79 56.90 31.70 58.21 32.61C59.51 33.52 60.92 34.59 62.06 35.78C63.19 36.98 64.10 38.44 65.03 39.77C65.95 41.10 66.84 42.42 67.59 43.75C68.35 45.08 69.01 46.41 69.55 47.74C70.09 49.07 70.45 50.39 70.83 51.72C71.22 53.05 71.67 54.38 71.85 55.71C72.03 57.04 71.98 58.36 71.92 59.69C71.85 61.02 71.78 62.35 71.44 63.68C71.11 65.00 70.81 66.65 69.89 67.66C68.97 68.67 67.23 69.52 65.90 69.75C64.58 69.99 63.25 69.56 61.92 69.08C60.59 68.60 59.25 67.77 57.94 66.85C56.62 65.93 55.22 64.75 54.02 63.54C52.81 62.34 51.70 60.94 50.71 59.62C49.72 58.31 48.90 56.97 48.08 55.64C47.25 54.31 46.41 52.98 45.78 51.65C45.15 50.33 44.72 49.00 44.29 47.67C43.87 46.34 43.39 45.01 43.21 43.69C43.03 42.36 43.11 41.03 43.21 39.70C43.31 38.37 43.30 37.03 43.82 35.72C44.34 34.40 45.24 32.79 46.32 31.80C47.40 30.81 48.99 30.02 50.30 29.77Z"

/** Draws a [SplashFrame] onto a native canvas. Holds its paths and paints so a frame allocates little. */
private class SplashPainter {
    private val full = PathParser.createPathFromPathData(MARK_PATH).apply { fillType = Path.FillType.EVEN_ODD }
    private val outer = PathParser.createPathFromPathData(MARK_PATH.substringBefore('Z') + "Z")
    private val hole = PathParser.createPathFromPathData(MARK_PATH.substringAfter('Z'))
    private val outerMeasure = PathMeasure(outer, true)
    private val holeMeasure = PathMeasure(hole, true)
    private val scratch = Path()
    private val pos = FloatArray(2)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // same glass as the launcher icon's mark: white at the top to cool grey at the base
        shader = LinearGradient(0f, 6f, 0f, 94f, 0xFFFFFFFF.toInt(), 0xFFC9CED7.toInt(), Shader.TileMode.CLAMP)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = android.graphics.Color.WHITE
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var u = 1f
    private var ox = 0f
    private var oy = 0f

    fun draw(c: Canvas, w: Float, h: Float, f: SplashFrame) {
        u = min(w, h) * MARK_FILL / 88f
        ox = w / 2f - 50f * u
        oy = h / 2f - 50f * u - 0.06f * h
        halo(c, f.haloAlpha, f.haloBlur, f.scale)
        trace(c, outerMeasure, f.draw, f.traceAlpha, 0.55f)
        trace(c, holeMeasure, f.drawIn, f.traceAlpha, 0.45f)
        body(c, f.fill, f.scale)
        spark(c, outerMeasure, f.draw, 0.16f, if (f.draw > 0f) f.sparkAlpha else 0f, 1.5f)
        spark(c, holeMeasure, f.drawIn, 0.2f, if (f.drawIn > 0f) f.sparkAlpha else 0f, 1.2f)
        sheen(c, f.sheenPos, f.sheenAlpha, f.scale)
    }

    /** Maps mark units onto the screen, scaled by [s] around the mark's visual centre (50, 52). */
    private inline fun inMark(c: Canvas, s: Float = 1f, block: () -> Unit) {
        c.save()
        c.translate(ox + 50f * u * (1f - s), oy + 52f * u * (1f - s))
        c.scale(u * s, u * s)
        block()
        c.restore()
    }

    private fun halo(c: Canvas, a: Float, blur: Float, s: Float) {
        if (a <= 0f) return
        c.saveLayer(null, null)
        inMark(c, s) {
            glowPaint.alpha = (a.coerceIn(0f, 1f) * 255).toInt()
            glowPaint.maskFilter = BlurMaskFilter(0.87f * blur, BlurMaskFilter.Blur.NORMAL)
            c.drawPath(outer, glowPaint)
            c.drawPath(hole, clearPaint) // the hole stays see-through
        }
        c.restore()
    }

    private fun body(c: Canvas, a: Float, s: Float) {
        if (a <= 0f) return
        fillPaint.alpha = (a * 255).toInt()
        inMark(c, s) { c.drawPath(full, fillPaint) }
    }

    private fun trace(c: Canvas, m: PathMeasure, p: Float, a: Float, width: Float) {
        if (a <= 0f || p <= 0f) return
        scratch.reset()
        m.getSegment(0f, m.length * p, scratch, true)
        linePaint.maskFilter = null
        linePaint.strokeWidth = width
        linePaint.alpha = (a * 255).toInt()
        inMark(c) { c.drawPath(scratch, linePaint) }
    }

    /** A comet on a contour: bright head with a glow, tail thinning and fading over [len] of the loop. */
    private fun spark(c: Canvas, m: PathMeasure, head: Float, len: Float, a: Float, width: Float) {
        if (a <= 0f) return
        val total = m.length
        val hd = head * total
        val chunks = 18
        val step = len * total / chunks
        linePaint.maskFilter = null
        inMark(c) {
            for (k in chunks - 1 downTo 0) {
                val fade = k / chunks.toFloat()
                scratch.reset()
                segmentWrapped(m, hd - (k + 1) * step, hd - k * step)
                linePaint.strokeWidth = width * (1f - 0.75f * fade)
                linePaint.alpha = (a * (1f - fade).pow(1.8f) * 255).toInt()
                c.drawPath(scratch, linePaint)
            }
            m.getPosTan(((hd % total) + total) % total, pos, null)
            glowPaint.alpha = (a * 255).toInt()
            glowPaint.maskFilter = null
            c.drawCircle(pos[0], pos[1], width * 0.9f, glowPaint)
            glowPaint.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(pos[0], pos[1], width * 0.9f, glowPaint)
            glowPaint.maskFilter = BlurMaskFilter(19f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(pos[0], pos[1], width * 0.9f, glowPaint)
        }
    }

    /** Appends [from]..[to] of the closed contour to [scratch], wrapping past its start. */
    private fun segmentWrapped(m: PathMeasure, from: Float, to: Float) {
        val total = m.length
        val f = from - floor(from / total) * total
        val t = f + (to - from)
        if (t <= total) {
            m.getSegment(f, t, scratch, true)
        } else {
            m.getSegment(f, total, scratch, true)
            m.getSegment(0f, t - total, scratch, true)
        }
    }

    /** A diagonal band of light swept across the glass, clipped to the mark. */
    private fun sheen(c: Canvas, p: Float, a: Float, s: Float) {
        if (a <= 0f) return
        val angle = 0.62f
        val dx = cos(angle) * 60f
        val dy = sin(angle) * 60f
        val band = 0.16f
        val stops = listOf(
            0f to 0f, p - band to 0f, p - band * 0.25f to a * 0.75f, p to a,
            p + band * 0.25f to a * 0.75f, p + band to 0f, 1f to 0f,
        ).filter { it.first in 0f..1f }.sortedBy { it.first }
        sheenPaint.shader = LinearGradient(
            50f - dx, 50f - dy, 50f + dx, 50f + dy,
            stops.map { android.graphics.Color.argb((it.second * 255).toInt(), 255, 255, 255) }.toIntArray(),
            stops.map { it.first }.toFloatArray(),
            Shader.TileMode.CLAMP,
        )
        inMark(c, s) {
            c.clipPath(full)
            c.drawRect(-20f, -20f, 120f, 120f, sheenPaint)
        }
    }
}
