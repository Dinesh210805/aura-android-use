package com.aura.aura_ui.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ========================================================================================
// AURA BRAND GRADIENT — the colored logo palette
// The app surfaces stay neutral/monochrome (see Color.kt). These brand colors are used
// ONLY for accents: the logo mark, the flowing "thinking" shader, the active mic/logo
// button, the screen-edge glow, and like/dislike highlights. Sampled from the rainbow
// logo (ic_launcher_foreground): magenta → orange → lime → cyan → violet.
// ========================================================================================

val AuraBrandMagenta = Color(0xFFE5197D)
val AuraBrandOrange = Color(0xFFA31621) // Replaced orange with blood red
val AuraBrandLime = Color(0xFF9FD82B)
val AuraBrandCyan = Color(0xFF16A5D6)
val AuraBrandViolet = Color(0xFF6C4CE0)

/** The five brand stops in spectral order (violet start reads best as a sweep origin). */
val AuraBrandStops = listOf(
    AuraBrandViolet,
    AuraBrandMagenta,
    AuraBrandOrange,
    AuraBrandLime,
    AuraBrandCyan,
)

/** Looped stops (first == last) for a seamless [Brush.sweepGradient] with no visible seam. */
val AuraBrandStopsLooped = AuraBrandStops + AuraBrandViolet

/** Diagonal brand gradient for filled accents (send button, active toggles). */
fun auraBrandLinear(): Brush = Brush.linearGradient(
    colors = listOf(AuraBrandViolet, AuraBrandMagenta, AuraBrandOrange, AuraBrandCyan),
)

/** Two-stop brand gradient for compact accents (mic idle→press ring, chips). */
fun auraBrandDuo(): Brush = Brush.linearGradient(listOf(AuraBrandViolet, AuraBrandMagenta))
