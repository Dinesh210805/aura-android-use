package com.aura.aura_ui.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.R

// Fonts ship in res/font (SIL Open Font License), so text never waits on a download or falls
// back to the system face. Instrument Sans is variable (weight 400-700); each weight picks its
// axis value, clamped to what the file has.
@OptIn(ExperimentalTextApi::class)
private fun sans(weight: FontWeight) = Font(
    R.font.instrument_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight.coerceIn(400, 700))),
)

/** Instrument Sans: every piece of UI text. */
val AuraBodyFamily = FontFamily(
    sans(FontWeight.Normal),
    sans(FontWeight.Medium),
    sans(FontWeight.SemiBold),
    sans(FontWeight.Bold),
    sans(FontWeight.ExtraBold),
)

/** Display text uses the same face as the body; hierarchy comes from size and weight. */
val AuraDisplayFamily = AuraBodyFamily

/** Instrument Serif: the greeting and page titles, the app's editorial voice. One weight only. */
val AuraSerifFamily = FontFamily(Font(R.font.instrument_serif, FontWeight.Normal))

/** Code, ids and raw payloads. */
val AuraMonoFamily = FontFamily.Monospace

// Legacy alias — existing callsites referencing AuraFontFamily continue to compile
val AuraFontFamily = AuraBodyFamily

// The Material type scale, on Instrument Sans.
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
