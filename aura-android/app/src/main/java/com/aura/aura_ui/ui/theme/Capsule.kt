package com.aura.aura_ui.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ============================================================================
// CAPSULE — the 2026-07-10 design language ("dark + light combined").
//
// Greige canvas, white sheet cards, charcoal grain cards, and ONE vermilion
// accent — with the black capsule strip as the app's control element (bottom
// nav on top-level screens). Dark cards live on the light canvas and light
// content lives inside dark cards: the combination IS the hierarchy.
//
// Reference: user-supplied mockup (skipper-app style) + the Ivory&Onyx/Capsule
// brainstorm (visual companion, this date). Blood red (Mono.Blood) remains the
// overlay's semantic red; Capsule.accent is the app-side attention/brand color.
// ============================================================================

data class CapsuleScheme(
    val isDark: Boolean,
    // page
    val canvas: Color,          // greige field everything sits on
    val card: Color,            // white sheet cards
    val cardSoft: Color,        // recessed chip/icon wells inside cards
    val line: Color,            // hairline dividers/borders on canvas + cards
    // charcoal ("the dark card world")
    val char: Color,            // charcoal card top
    val charDeep: Color,        // charcoal card bottom
    val onChar: Color,          // primary text on charcoal
    val onCharDim: Color,       // secondary text on charcoal
    val charLine: Color,        // hairlines inside charcoal
    // text on canvas/cards
    val ink: Color,
    val sub: Color,
    // the one accent
    val accent: Color,
    val accentSoft: Color,      // soft field behind accent icons/badges
    val onAccent: Color,
    // hero gradient (peach light field inside the hero card)
    val heroCore: Color,
    val heroMid: Color,
    val heroEdge: Color,
    // capsule nav strip
    val capsule: Color,
    val onCapsule: Color,
    val onCapsuleDim: Color,
    // status
    val ok: Color,
)

object Capsule {
    /** One radius language, softer than Mono's (reference uses large soft radii). */
    val ShapeHero = RoundedCornerShape(26.dp)
    val ShapeCard = RoundedCornerShape(22.dp)
    val ShapeChipWell = RoundedCornerShape(12.dp)
    val ShapePill = RoundedCornerShape(percent = 50)

    @Composable
    fun scheme(): CapsuleScheme = LocalAura.current.toCapsule()
}

/** The peach hero field (warm core fading outward to canvas). */
fun CapsuleScheme.heroBrush(): Brush = Brush.radialGradient(
    colors = listOf(heroCore, heroMid, heroEdge),
)

/** Charcoal card field (subtle top-light like a lit slab). */
fun CapsuleScheme.charBrush(): Brush = Brush.verticalGradient(listOf(char, charDeep))
