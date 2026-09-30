package com.aura.aura_ui.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Apple-Inspired Monochrome Color System
 * Pure black & white with elegant gray gradation
 * Glassmorphism + Skeuomorphic depth cues
 */
object AppleColors {
    
    // ============================================================================
    // SYSTEM BACKGROUNDS — Monochrome
    // ============================================================================
    
    object Light {
        val Background = Color(0xFFF2F2F2)
        val BackgroundSecondary = Color(0xFFFFFFFF)
        val BackgroundTertiary = Color(0xFFE8E8E8)
        
        val Surface = Color(0xFFFFFFFF)
        val SurfaceSecondary = Color(0xFFF5F5F5)
        val SurfaceElevated = Color(0xFFFFFFFF)
        
        val Separator = Color(0xFFC6C6C6)
        val SeparatorOpaque = Color(0xFFDCDCDC)
        
        val Fill = Color(0x33787878)
        val FillSecondary = Color(0x29787878)
        val FillTertiary = Color(0x1F787878)
        val FillQuaternary = Color(0x14787878)
    }
    
    object Dark {
        val Background = Color(0xFF000000)
        val BackgroundSecondary = Color(0xFF1C1C1C)
        val BackgroundTertiary = Color(0xFF2C2C2C)
        
        val Surface = Color(0xFF1C1C1C)
        val SurfaceSecondary = Color(0xFF2C2C2C)
        val SurfaceElevated = Color(0xFF2C2C2C)
        
        val Separator = Color(0xFF383838)
        val SeparatorOpaque = Color(0xFF484848)
        
        val Fill = Color(0x5C787878)
        val FillSecondary = Color(0x52787878)
        val FillTertiary = Color(0x3D787878)
        val FillQuaternary = Color(0x2E787878)
    }
    
    // ============================================================================
    // SYSTEM COLORS — All Monochrome
    // ============================================================================
    
    val Blue = Color(0xFFA0A0A0)
    val BlueDark = Color(0xFFBBBBBB)
    
    val Green = Color(0xFFD0D0D0)
    val GreenDark = Color(0xFFE0E0E0)
    
    val Orange = Color(0xFF909090)
    val OrangeDark = Color(0xFFA0A0A0)
    
    val Red = Color(0xFF505050)
    val RedDark = Color(0xFF606060)
    
    val Teal = Color(0xFFB0B0B0)
    val TealDark = Color(0xFFC8C8C8)
    
    val Purple = Color(0xFF858585)
    val PurpleDark = Color(0xFF9A9A9A)
    
    val Pink = Color(0xFF707070)
    val PinkDark = Color(0xFF808080)
    
    val Yellow = Color(0xFFCCCCCC)
    val YellowDark = Color(0xFFDDDDDD)
    
    val Indigo = Color(0xFF7A7A7A)
    val IndigoDark = Color(0xFF909090)
    
    val Cyan = Color(0xFFBBBBBB)
    val CyanDark = Color(0xFFCCCCCC)
    
    val Mint = Color(0xFFC0C0C0)
    val MintDark = Color(0xFFD8D8D8)
    
    // ============================================================================
    // LABEL COLORS — Text hierarchy
    // ============================================================================
    
    object LabelLight {
        val Primary = Color(0xFF000000)
        val Secondary = Color(0x993C3C3C)
        val Tertiary = Color(0x4D3C3C3C)
        val Quaternary = Color(0x2E3C3C3C)
    }
    
    object LabelDark {
        val Primary = Color(0xFFFFFFFF)
        val Secondary = Color(0x99E8E8E8)
        val Tertiary = Color(0x4DE8E8E8)
        val Quaternary = Color(0x29E8E8E8)
    }
    
    // ============================================================================
    // AURA BRAND — Monochrome
    // ============================================================================
    
    val AuraPrimary = Color(0xFFFFFFFF)
    val AuraPrimaryDark = Color(0xFFE0E0E0)
    
    val AuraSecondary = Color(0xFFA0A0A0)
    val AuraSecondaryDark = Color(0xFFBBBBBB)
    
    val AuraTertiary = Color(0xFF6B6B6B)
    val AuraTertiaryDark = Color(0xFF808080)
}

/**
 * Overlay-specific monochrome colors with glassmorphism
 */
object AppleOverlayColors {
    val Scrim = Color(0xFF000000).copy(alpha = 0.5f)
    val ScrimHeavy = Color(0xFF000000).copy(alpha = 0.7f)
    
    // Glassmorphism surfaces
    val GlassLight = Color(0xFFFFFFFF).copy(alpha = 0.72f)
    val GlassDark = Color(0xFF1C1C1C).copy(alpha = 0.82f)
    
    val InputBarLight = Color(0xFFFFFFFF)
    val InputBarDark = Color(0xFF2C2C2C)
    
    val SheetLight = Color(0xFFF2F2F2)
    val SheetDark = Color(0xFF1C1C1C)
}
