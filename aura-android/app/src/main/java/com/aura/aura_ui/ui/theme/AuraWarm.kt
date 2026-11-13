package com.aura.aura_ui.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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

@Composable
fun rememberWarmScheme(): WarmScheme = LocalAura.current.toWarm()

/** Subtle warm canvas gradient used behind every screen (via AuraScreenScaffold). */
fun WarmScheme.canvasBrush(): Brush = Brush.verticalGradient(listOf(canvasTop, canvasBottom))

/** The rich Home background gradient (cream → peach → coral). */
fun WarmScheme.homeBrush(): Brush = Brush.verticalGradient(listOf(homeTop, homeMid, homeBottom))

/** The bold primary-card gradient (coral → brick, AURA's red family). */
fun WarmScheme.heroBrush(): Brush = Brush.linearGradient(listOf(heroA, heroB))
