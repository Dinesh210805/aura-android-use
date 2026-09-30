package com.aura.aura_ui.ui.theme

import androidx.compose.ui.graphics.Color

// ========================================================================================
// AURA MONOCHROME DESIGN SYSTEM — PREMIUM BLACK & WHITE PALETTE
// Glassmorphism · Skeuomorphism · Pure Monochrome Elegance
// ========================================================================================

// === CORE BRAND IDENTITY — Pure Monochrome ===
val AuraPrimary = Color(0xFFFFFFFF)         // White — Primary brand
val AuraPrimaryVariant = Color(0xFFE5E5E5)  // Soft white
val AuraPrimaryLight = Color(0xFFFFFFFF)    // Pure white
val AuraPrimaryDark = Color(0xFFD4D4D4)     // Dim white

// === SECONDARY BRAND — Shades of Gray ===
val AuraSecondary = Color(0xFFA0A0A0)       // Mid gray — Sophistication
val AuraSecondaryVariant = Color(0xFF8A8A8A)
val AuraSecondaryLight = Color(0xFFBDBDBD)
val AuraSecondaryDark = Color(0xFF6E6E6E)

// === ACCENT PALETTE — Dark Tones ===
val AuraTertiary = Color(0xFF4A4A4A)        // Charcoal
val AuraTertiaryLight = Color(0xFF6E6E6E)
val AuraTertiaryDark = Color(0xFF2E2E2E)

val AuraAccent = Color(0xFFD4D4D4)          // Silver accent
val AuraAccentLight = Color(0xFFE8E8E8)
val AuraAccentDark = Color(0xFFB0B0B0)

// === PREMIUM NEUTRAL SYSTEM ===
val AuraNeutral950 = Color(0xFF000000)      // True black
val AuraNeutral900 = Color(0xFF0A0A0A)      // Near black
val AuraNeutral800 = Color(0xFF171717)      // Darkest surface
val AuraNeutral700 = Color(0xFF262626)      // Dark surface
val AuraNeutral600 = Color(0xFF404040)      // Secondary text dark
val AuraNeutral500 = Color(0xFF6B6B6B)      // Body text
val AuraNeutral400 = Color(0xFF8F8F8F)      // Placeholder text
val AuraNeutral300 = Color(0xFFB3B3B3)      // Disabled elements
val AuraNeutral200 = Color(0xFFD4D4D4)      // Subtle borders
val AuraNeutral100 = Color(0xFFEDEDED)      // Background secondary
val AuraNeutral50 = Color(0xFFF7F7F7)       // Background primary
val AuraWhite = Color(0xFFFFFFFF)            // Pure white

// === STATUS COLORS — Monochrome Luminance Scale ===
val AuraSuccess = Color(0xFFE0E0E0)         // Light gray — Success
val AuraSuccessLight = Color(0xFFF2F2F2)
val AuraSuccessDark = Color(0xFFBBBBBB)
val AuraSuccessContainer = Color(0xFFF7F7F7)

val AuraWarning = Color(0xFF8F8F8F)         // Medium gray — Warning
val AuraWarningLight = Color(0xFFD4D4D4)
val AuraWarningDark = Color(0xFF6B6B6B)
val AuraWarningContainer = Color(0xFFEDEDED)

val AuraError = Color(0xFF3A3A3A)           // Dark gray — Error
val AuraErrorLight = Color(0xFF6B6B6B)
val AuraErrorDark = Color(0xFF1A1A1A)
val AuraErrorContainer = Color(0xFF4A4A4A)

val AuraInfo = Color(0xFFC0C0C0)            // Silver — Info
val AuraInfoLight = Color(0xFFE0E0E0)
val AuraInfoDark = Color(0xFF9E9E9E)
val AuraInfoContainer = Color(0xFFF0F0F0)

// === AI ASSISTANT STATE COLORS — Monochrome ===
val AuraListening = Color(0xFFFFFFFF)       // White — Active recording
val AuraProcessing = Color(0xFFB0B0B0)      // Silver — AI thinking
val AuraResponding = Color(0xFFD4D4D4)      // Light gray — AI speaking
val AuraIdle = Color(0xFF6B6B6B)            // Dark gray — Waiting
val AuraConnecting = Color(0xFF8F8F8F)      // Medium gray — Connecting
val AuraError_State = Color(0xFF3A3A3A)     // Charcoal — Error state

// === MONOCHROME GRADIENT COLLECTIONS ===
object AuraGradients {
    val Primary = listOf(
        Color(0xFFFFFFFF),    // White
        Color(0xFFD4D4D4),    // Silver
    )

    val PrimarySubtle = listOf(
        Color(0x20FFFFFF),    // Ghost white
        Color(0x20D4D4D4),    // Ghost silver
    )

    val AI = listOf(
        Color(0xFFFFFFFF),    // White
        Color(0xFFD4D4D4),    // Silver
        Color(0xFFA0A0A0),    // Mid gray
    )

    val Success = listOf(
        Color(0xFFE0E0E0),
        Color(0xFFBBBBBB),
    )

    val Warning = listOf(
        Color(0xFF8F8F8F),
        Color(0xFF6B6B6B),
    )

    val Error = listOf(
        Color(0xFF3A3A3A),
        Color(0xFF1A1A1A),
    )

    // Glassmorphism gradients
    val Glass = listOf(
        Color(0x33FFFFFF),    // Frosted light
        Color(0x1AFFFFFF),    // Subtle frost
    )

    val GlassDark = listOf(
        Color(0x33000000),    // Dark frost
        Color(0x1A000000),    // Subtle dark frost
    )

    val GlassBorder = listOf(
        Color(0x40FFFFFF),    // Glass edge highlight
        Color(0x15FFFFFF),    // Glass edge fade
    )

    val Overlay = listOf(
        Color(0xB3000000),    // Heavy overlay
        Color(0x00000000),    // Transparent
    )

    // Skeuomorphic surface gradients (subtle 3D depth)
    val SurfaceRaised = listOf(
        Color(0xFFF7F7F7),    // Top highlight
        Color(0xFFE8E8E8),    // Bottom shadow
    )

    val SurfacePressed = listOf(
        Color(0xFFE0E0E0),    // Top shadow
        Color(0xFFEDEDED),    // Bottom highlight
    )

    val SurfaceDarkRaised = listOf(
        Color(0xFF2A2A2A),    // Top highlight
        Color(0xFF171717),    // Bottom shadow
    )
}

// === SURFACE SYSTEM ===
val AuraSurfacePrimary = Color(0xFFF7F7F7)
val AuraSurfaceSecondary = Color(0xFFFFFFFF)
val AuraSurfaceTertiary = Color(0xFFEDEDED)
val AuraSurfaceElevated = Color(0xFFFFFFFF)
val AuraSurfaceContainer = Color(0xFFF5F5F5)
val AuraSurfaceContainerHigh = Color(0xFFEDEDED)

// Dark theme surfaces
val AuraSurfacePrimaryDark = Color(0xFF000000)
val AuraSurfaceSecondaryDark = Color(0xFF0A0A0A)
val AuraSurfaceTertiaryDark = Color(0xFF171717)
val AuraSurfaceElevatedDark = Color(0xFF1A1A1A)

// === INTERACTIVE STATES ===
val AuraInteractive = AuraNeutral800
val AuraInteractiveHover = AuraNeutral700
val AuraInteractivePressed = AuraNeutral600
val AuraInteractiveDisabled = AuraNeutral300

val AuraFocus = AuraWhite
val AuraFocusRing = Color(0x4DFFFFFF)
val AuraSelection = Color(0x1AFFFFFF)

// === BORDER SYSTEM ===
val AuraBorder = AuraNeutral200
val AuraBorderHover = AuraNeutral300
val AuraBorderFocus = AuraWhite
val AuraBorderError = AuraError
val AuraBorderSuccess = AuraSuccess

val AuraBorderDark = AuraNeutral700
val AuraBorderHoverDark = AuraNeutral600
val AuraBorderFocusDark = AuraWhite

// === LEGACY COMPATIBILITY ===
val Purple80 = AuraSecondaryLight
val PurpleGrey80 = AuraNeutral400
val Pink80 = AuraAccentLight
val Purple40 = AuraSecondaryDark
val PurpleGrey40 = AuraNeutral600
val Pink40 = AuraAccentDark

val AuraGradientPrimary = AuraGradients.Primary
val AuraGradientSecondary = listOf(AuraSecondary, AuraSecondaryLight)
val AuraGradientAccent = listOf(AuraTertiary, AuraTertiaryLight)
val AuraGradientSuccess = AuraGradients.Success
val AuraGradientWarning = AuraGradients.Warning
val AuraGradientError = AuraGradients.Error
