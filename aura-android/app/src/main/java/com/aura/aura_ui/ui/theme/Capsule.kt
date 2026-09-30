package com.aura.aura_ui.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.presentation.components.rememberMonoScheme

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

private val CapsuleLight = CapsuleScheme(
    isDark = false,
    canvas = Color(0xFFEAE8E1),
    card = Color(0xFFFFFFFF),
    cardSoft = Color(0xFFF5F3ED),
    line = Color(0xFFE2DFD5),
    char = Color(0xFF2B2723),
    charDeep = Color(0xFF191613),
    onChar = Color(0xFFF4F1EA),
    onCharDim = Color(0xFFA5A199),
    charLine = Color(0x1AFFFFFF),
    ink = Color(0xFF1C1A17),
    sub = Color(0xFF8A867C),
    accent = Color(0xFFE4572E),
    accentSoft = Color(0xFFF9DDCE),
    onAccent = Color(0xFFFFFFFF),
    heroCore = Color(0xFFF6CDB2),
    heroMid = Color(0xFFF5E6D8),
    heroEdge = Color(0xFFF4F1EA),
    capsule = Color(0xFF0F0E0C),
    onCapsule = Color(0xFFF4F1EA),
    onCapsuleDim = Color(0xFF8F8B82),
    ok = Color(0xFF3F8F5A),
)

// Dark theme keeps the same combined language, inverted: charcoal canvas,
// dark sheet cards, and the hero/peach light glowing FROM the dark.
private val CapsuleDark = CapsuleScheme(
    isDark = true,
    canvas = Color(0xFF151311),
    card = Color(0xFF1F1C19),
    cardSoft = Color(0xFF282520),
    line = Color(0xFF2E2B26),
    char = Color(0xFF35302A),
    charDeep = Color(0xFF1B1916),
    onChar = Color(0xFFF4F1EA),
    onCharDim = Color(0xFFA5A199),
    charLine = Color(0x1AFFFFFF),
    ink = Color(0xFFF0EDE5),
    sub = Color(0xFF97938A),
    accent = Color(0xFFE86A43),
    accentSoft = Color(0xFF3C241B),
    onAccent = Color(0xFFFFFFFF),
    heroCore = Color(0xFF5E3B28),
    heroMid = Color(0xFF3A2A20),
    heroEdge = Color(0xFF1F1C19),
    capsule = Color(0xFF0B0A09),
    onCapsule = Color(0xFFF4F1EA),
    onCapsuleDim = Color(0xFF7E7A72),
    ok = Color(0xFF5CA877),
)

object Capsule {
    /** One radius language, softer than Mono's (reference uses large soft radii). */
    val ShapeHero = RoundedCornerShape(26.dp)
    val ShapeCard = RoundedCornerShape(22.dp)
    val ShapeChipWell = RoundedCornerShape(12.dp)
    val ShapePill = RoundedCornerShape(percent = 50)

    @Composable
    fun scheme(): CapsuleScheme = if (rememberMonoScheme().isDark) CapsuleDark else CapsuleLight
}

/** The peach hero field (warm core fading outward to canvas). */
fun CapsuleScheme.heroBrush(): Brush = Brush.radialGradient(
    colors = listOf(heroCore, heroMid, heroEdge),
)

/** Charcoal card field (subtle top-light like a lit slab). */
fun CapsuleScheme.charBrush(): Brush = Brush.verticalGradient(listOf(char, charDeep))
