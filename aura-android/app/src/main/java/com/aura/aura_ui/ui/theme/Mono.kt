package com.aura.aura_ui.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ========================================================================================
// MONO — the production design language: "Monochrome + a drop of blood".
//
// White-dominant canvas, black ("ink") cards and pills, blood red reserved strictly for
// semantic attention: live/recording, destructive actions, broken permissions, active-run
// dot. Red is never decorative — anything black is content/action, anything white is
// canvas, red means "look here now".
//
// Spec: docs/superpowers/specs/2026-07-08-production-ui-redesign-design.md §2
// ========================================================================================

object Mono {
    // --- Canvas (white world) ---
    val Canvas = Color(0xFFFAFAF8)          // warm off-white app background
    val CanvasHigh = Color(0xFFFFFFFF)      // elevated white surfaces, sheets
    val Outline = Color(0xFFE8E8E4)         // hairline borders on white
    val TextPrimary = Color(0xFF111111)     // primary text on canvas
    val TextSecondary = Color(0xFF6B6B68)   // secondary text on canvas

    // --- Ink (black world) ---
    val Ink = Color(0xFF0A0A0A)             // black cards, pills, primary buttons
    val InkSoft = Color(0xFF171717)         // nested dark surface on ink
    val InkPressed = Color(0xFF262626)      // pressed state of ink surfaces
    val TextOnInk = Color(0xFFF5F5F2)       // primary text on ink
    val TextOnInkDim = Color(0xFF9C9C98)    // secondary text on ink
    val OutlineOnInk = Color(0xFF2E2E2B)    // hairline separators inside ink surfaces

    // --- Blood (semantic attention only) ---
    val Blood = Color(0xFFA31621)           // live dot, destructive, broken-permission
    val BloodPressed = Color(0xFF7A1019)
    val BloodContainer = Color(0xFFFBEBEC)  // soft red field behind warning banners

    // --- Toggle green (the ONE sanctioned color exception) ---
    // iOS system green for the "on" state of switches, per the Settings-screen brief.
    // Used ONLY as a switch checked-track fill — never as content, canvas, or accent.
    val ToggleGreen = Color(0xFF34C759)
    val ToggleTrackOff = Color(0xFFE4E4E1)      // light-mode off track
    val ToggleTrackOffDark = Color(0xFF3A3A3A)  // dark-mode off track

    // --- Spacing scale (one rhythm app-wide) ---
    val ScreenH = 24.dp                     // standard horizontal screen padding

    object Space {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    // --- Shape system (one radius language app-wide) ---
    val ShapeCard = RoundedCornerShape(24.dp)
    val ShapeCardSmall = RoundedCornerShape(16.dp)
    val ShapeChip = RoundedCornerShape(12.dp)
    val ShapePill = RoundedCornerShape(percent = 50)

    // --- Motion (ms) ---
    const val MotionFast = 150
    const val MotionNormal = 220
    const val MotionSlow = 400

    /** Resolve the theme-aware scheme for list/settings surfaces. */
    fun scheme(dark: Boolean): MonoScheme = if (dark) DarkScheme else LightScheme
}

/**
 * Theme-aware colors for the grouped-list surfaces (settings/hub/detail screens):
 * light = white cards on warm-gray canvas, dark = charcoal cards on near-black.
 * Blood red stays identical in both — it is semantic, not decorative.
 */
data class MonoScheme(
    val canvas: Color,
    val card: Color,
    val chip: Color,            // small rounded square behind row icons
    val chipContent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val outline: Color,
    val bloodContainer: Color,   // soft field behind warning banners
    val isDark: Boolean,
)

// Capsule redesign (2026-07-10): the grouped-list surfaces now sit on the
// greige/charcoal Capsule palette. Mono.Ink/Blood constants are unchanged —
// the overlay (frozen) keeps its exact colors.
private val LightScheme = MonoScheme(
    canvas = Color(0xFFEAE8E1),
    card = Color(0xFFFFFFFF),
    chip = Color(0xFFF5F3ED),
    chipContent = Color(0xFF1C1A17),
    textPrimary = Color(0xFF1C1A17),
    textSecondary = Color(0xFF8A867C),
    divider = Color(0xFFEFEDE5),
    outline = Color(0xFFE2DFD5),
    bloodContainer = Color(0xFFF9DDCE),
    isDark = false,
)

private val DarkScheme = MonoScheme(
    canvas = Color(0xFF0A0908),
    card = Color(0xFF141210),
    chip = Color(0xFF1C1A18),
    chipContent = Color(0xFFF0EDE5),
    textPrimary = Color(0xFFF0EDE5),
    textSecondary = Color(0xFF97938A),
    divider = Color(0xFF1A1816),
    outline = Color(0xFF22201D),
    bloodContainer = Color(0xFF260D10),
    isDark = true,
)
