package com.aura.aura_ui.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.R

// Google Fonts Provider
val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

// Plus Jakarta Sans — razor-sharp, geometric, ultra-crisp modern sans
private val PlusJakartaSansFont = GoogleFont("Plus Jakarta Sans")

// JetBrains Mono — razor-sharp, technical monospace companion
private val JetBrainsMonoFont = GoogleFont("JetBrains Mono")

/**
 * Display / Hero font family: Sharp, confident geometric grotesque.
 */
val AuraDisplayFamily = FontFamily(
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.ExtraBold),
)

/**
 * Body / UI font family: Crisp, balanced, high-legibility UI typography.
 */
val AuraBodyFamily = FontFamily(
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = PlusJakartaSansFont, fontProvider = provider, weight = FontWeight.Bold),
)

/**
 * Monospace font family for code, pills, data metrics, and technical labels.
 */
val AuraMonoFamily = FontFamily(
    Font(googleFont = JetBrainsMonoFont, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = JetBrainsMonoFont, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = JetBrainsMonoFont, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = JetBrainsMonoFont, fontProvider = provider, weight = FontWeight.Bold),
)

// Legacy alias — existing callsites referencing AuraFontFamily continue to compile
val AuraFontFamily = AuraBodyFamily

// AURA Razor-Sharp Minimalist Typography System
val Typography = Typography(
    // ── Display ── large hero text, screen titles
    displayLarge = TextStyle(
        fontFamily = AuraDisplayFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 54.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = AuraDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.0).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = AuraDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
    ),

    // ── Headline ── section headers
    headlineLarge = TextStyle(
        fontFamily = AuraDisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = AuraDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AuraDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),

    // ── Title ── card headers, list titles
    titleLarge = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),

    // ── Body ── main content
    bodyLarge = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),

    // ── Label ── buttons, chips, small UI elements
    labelLarge = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AuraBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)
