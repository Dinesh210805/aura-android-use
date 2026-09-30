package com.aura.aura_ui.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aura.aura_ui.presentation.components.rememberMonoScheme

// ============================================================================
// AURA WARM — the warm gradient identity (Home + app-wide canvas).
//
// A deliberate warming of the mono system toward AURA's own "drop of blood":
// the canvas is a soft cream→peach gradient instead of flat white, the primary
// action card is a bold coral→brick gradient (never black), and blood red is
// still reserved for the live/attention dot. Theme-aware (light + dark).
// ============================================================================

data class WarmScheme(
    val isDark: Boolean,
    // page canvas (subtle, used app-wide by the scaffold)
    val canvasTop: Color,
    val canvasBottom: Color,
    // rich Home background
    val homeTop: Color,
    val homeMid: Color,
    val homeBottom: Color,
    // bold primary "hero" card
    val heroA: Color,
    val heroB: Color,
    val onHero: Color,
    val onHeroDim: Color,
    // frosted secondary cards
    val card: Color,
    val cardBorder: Color,
    // text on warm canvas
    val textPrimary: Color,
    val textSecondary: Color,
    // circular icon buttons on canvas
    val iconButton: Color,
    val iconButtonContent: Color,
    val blood: Color,
)

private val WarmLight = WarmScheme(
    isDark = false,
    // Capsule redesign (2026-07-10): the app-wide canvas is the greige field.
    canvasTop = Color(0xFFEDEBE4),
    canvasBottom = Color(0xFFE7E5DD),
    homeTop = Color(0xFFFDF2EC),
    homeMid = Color(0xFFF8D9C9),
    homeBottom = Color(0xFFF2B49B),
    heroA = Color(0xFFF19274),
    heroB = Color(0xFFCC4B33),
    onHero = Color(0xFFFFFFFF),
    onHeroDim = Color(0xFFFFE7DE),
    card = Color(0xCCFFFFFF),          // frosted peach over the gradient
    cardBorder = Color(0x33B5745C),
    textPrimary = Color(0xFF2A1A13),
    textSecondary = Color(0xFF82685E),
    iconButton = Color(0xE6FFFFFF),
    iconButtonContent = Color(0xFF2A1A13),
    blood = Mono.Blood,
)

private val WarmDark = WarmScheme(
    isDark = true,
    // Capsule redesign (2026-07-10): charcoal canvas, no red cast.
    canvasTop = Color(0xFF171512),
    canvasBottom = Color(0xFF131110),
    homeTop = Color(0xFF161010),
    homeMid = Color(0xFF241511),
    homeBottom = Color(0xFF321912),
    heroA = Color(0xFFE07C5C),
    heroB = Color(0xFFB23A2A),
    onHero = Color(0xFFFFFFFF),
    onHeroDim = Color(0xFFFFE7DE),
    card = Color(0xB32A1C17),
    cardBorder = Color(0x33E0987C),
    textPrimary = Color(0xFFF3E7E1),
    textSecondary = Color(0xFFB49E95),
    iconButton = Color(0x1AFFFFFF),
    iconButtonContent = Color(0xFFF3E7E1),
    blood = Mono.Blood,
)

@Composable
fun rememberWarmScheme(): WarmScheme = if (rememberMonoScheme().isDark) WarmDark else WarmLight

/** Subtle warm canvas gradient used behind every screen (via AuraScreenScaffold). */
fun WarmScheme.canvasBrush(): Brush = Brush.verticalGradient(listOf(canvasTop, canvasBottom))

/** The rich Home background gradient (cream → peach → coral). */
fun WarmScheme.homeBrush(): Brush = Brush.verticalGradient(listOf(homeTop, homeMid, homeBottom))

/** The bold primary-card gradient (coral → brick, AURA's red family). */
fun WarmScheme.heroBrush(): Brush = Brush.linearGradient(listOf(heroA, heroB))
