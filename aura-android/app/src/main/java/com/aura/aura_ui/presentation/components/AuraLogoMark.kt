package com.aura.aura_ui.presentation.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.R
import com.aura.aura_ui.ui.theme.AuraBrandCyan
import com.aura.aura_ui.ui.theme.AuraBrandMagenta
import com.aura.aura_ui.ui.theme.AuraBrandStopsLooped

// ============================================================================
// AURA LOGO MARK — the real brand logo (ic_launcher_foreground) as a live element.
//
// [AuraLogoButton] replaces the mic in the input pill: the logo in solid white at
// rest, and — when [active] — filled with the brand gradient flowing like liquid
// energy (AGSL shader on API 33+, animated sweep fallback below), with a soft bloom
// and an occasional glass light-sweep. The logo SHAPE is the actual launcher asset,
// used as a DstIn alpha mask so the flow is clipped exactly to the mark (hole and
// all). [AuraLogoAvatar] is the static colored logo shown beside AI replies.
// ============================================================================

/** AGSL port of the WebGL thinking shader: domain-warped, speed-varying swirl through the palette. */
private const val AURA_FLOW_AGSL = """
uniform float uTime;
uniform float2 uRes;
half3 pal(float x) {
    half3 c0 = half3(0.902, 0.098, 0.490);
    half3 c1 = half3(1.0,   0.478, 0.0);
    half3 c2 = half3(0.624, 0.847, 0.169);
    half3 c3 = half3(0.086, 0.647, 0.839);
    half3 c4 = half3(0.424, 0.298, 0.878);
    x = fract(x) * 5.0;
    float i = floor(x);
    float f = smoothstep(0.0, 1.0, fract(x));
    half3 a; half3 b;
    if (i < 1.0)      { a = c0; b = c1; }
    else if (i < 2.0) { a = c1; b = c2; }
    else if (i < 3.0) { a = c2; b = c3; }
    else if (i < 4.0) { a = c3; b = c4; }
    else              { a = c4; b = c0; }
    return mix(a, b, half(f));
}
half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * uRes) / uRes.y;
    float t = uTime * 0.08;
    float2 p = uv * 1.6;
    p += 0.25 * float2(sin(p.y * 2.4 + t * 1.7), cos(p.x * 2.1 - t * 1.3));
    float a = t * 0.6 + sin(t * 0.5) * 0.3;
    float ca = cos(a); float sa = sin(a);
    p = float2(ca * p.x - sa * p.y, sa * p.x + ca * p.y);
    float ang = atan(p.y, p.x);
    float rad = length(p);
    float g = ang / 6.2831853 + t * 0.5 + 0.35 * sin(rad * 3.0 - t * 2.0);
    half3 col = pal(g);
    col *= half(0.82 + 0.30 * (1.0 - rad));
    return half4(col, 1.0);
}
"""

/**
 * The Aura logo as the primary input-bar button.
 *
 * @param active when true the mark fills with the flowing brand gradient (thinking/speaking);
 *   when false it shows a clean white logo (idle). Transitions cross-fade smoothly.
 */
@Composable
fun AuraLogoButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    contentDescription: String = "Aura",
) {
    val logo = ImageBitmap.imageResource(R.mipmap.ic_launcher_foreground)

    val activeAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "logoActive",
    )

    // 60fps time driver — runs only while the mark is (or is fading to/from) active.
    var time by remember { mutableFloatStateOf(0f) }
    val running = active || activeAlpha > 0.01f
    LaunchedEffect(running) {
        while (running) {
            withInfiniteAnimationFrameMillis { time = it / 1000f }
        }
    }

    val shader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(AURA_FLOW_AGSL) else null
    }

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(
                brush = Brush.radialGradient(listOf(Color(0xFF26262B), Color(0xFF141416))),
                shape = CircleShape,
            )
            .semantics { this.contentDescription = contentDescription },
    ) {
        // Soft brand bloom behind the mark while active (unmasked).
        if (activeAlpha > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = this.size.minDimension * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AuraBrandMagenta.copy(alpha = 0.34f * activeAlpha),
                            AuraBrandCyan.copy(alpha = 0.16f * activeAlpha),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                    center = center,
                )
            }
        }

        // Idle: the logo in solid white.
        if (activeAlpha < 1f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - activeAlpha },
            ) {
                drawLogo(logo, ColorFilter.tint(Color.White))
            }
        }

        // Active: flowing gradient, masked to the exact logo alpha (DstIn on an offscreen layer).
        if (activeAlpha > 0f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.alpha = activeAlpha
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
            ) {
                if (shader != null) {
                    shader.setFloatUniform("uTime", time)
                    shader.setFloatUniform("uRes", this.size.width, this.size.height)
                    drawRect(brush = ShaderBrush(shader))
                } else {
                    drawRect(brush = Brush.linearGradient(AuraBrandStopsLooped))
                }
                drawLightSweep(time)
                // Mask everything above to the logo's alpha.
                drawLogo(logo, blendMode = BlendMode.DstIn)
            }
        }
    }
}

/** Static colored logo mark (no animation) for AI reply avatars. */
@Composable
fun AuraLogoAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val logo = ImageBitmap.imageResource(R.mipmap.ic_launcher_foreground)
    Canvas(modifier = modifier.size(size)) {
        drawLogo(logo)
    }
}

/** Static white AURA mark for dark branded controls such as the Browser pill. */
@Composable
fun AuraWhiteLogo(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val logo = ImageBitmap.imageResource(R.mipmap.ic_launcher_foreground)
    Canvas(modifier = modifier.size(size)) {
        drawLogo(logo, ColorFilter.tint(Color.White))
    }
}

/** Draw the (square) logo bitmap scaled to fill the draw area, slightly enlarged past the safe zone. */
private fun DrawScope.drawLogo(
    logo: ImageBitmap,
    colorFilter: ColorFilter? = null,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    // The launcher foreground carries transparent safe-zone padding; scale up ~1.28× to
    // make the mark read at button size.
    val scale = 1.28f
    val w = (this.size.width * scale)
    val h = (this.size.height * scale)
    val dx = ((this.size.width - w) / 2f)
    val dy = ((this.size.height - h) / 2f)
    drawImage(
        image = logo,
        dstOffset = IntOffset(dx.toInt(), dy.toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
        colorFilter = colorFilter,
        blendMode = blendMode,
    )
}

/** A single occasional glass highlight travelling diagonally across the mark. */
private fun DrawScope.drawLightSweep(timeSec: Float) {
    val period = 5.5f
    val phase = (timeSec % period) / period
    val eased = if (phase < 0.68f) -0.3f else (phase - 0.68f) / 0.32f * 1.6f - 0.3f
    val sx = eased * this.size.width
    val bandW = this.size.width * 0.16f
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
            start = Offset(sx - bandW, 0f),
            end = Offset(sx + bandW, this.size.height),
        ),
    )
}
