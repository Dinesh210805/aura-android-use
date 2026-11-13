package com.aura.aura_ui.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The root theme for every Compose surface in the app and the overlay.
 *
 * - Contract: provides [LocalAura] and a Material colour scheme built from it, so Material
 *   components (switches, fields, dialogs, menus) match the custom ones. With [darkTheme] null
 *   it follows the user's choice in [AppearanceStore]; the overlay passes `true`.
 * - Reads/Writes: sets the status and navigation bar icon colours of the hosting Activity, when
 *   there is one. The overlay has no Activity window.
 */
@Composable
fun AuraUITheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val choice by AppearanceStore.theme.collectAsState()
    val accent by AppearanceStore.accent.collectAsState()
    val style by AppearanceStore.style.collectAsState()
    val system = isSystemInDarkTheme()
    val dark = darkTheme ?: when (choice) {
        ThemeChoice.SYSTEM -> system
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    // The sky follows the clock: re-read it every few minutes, as MilkLogger does.
    val hour by produceState(currentHour()) {
        while (true) {
            delay(SKY_TICK_MS)
            value = currentHour()
        }
    }
    val colors = remember(dark, accent, style, hour) {
        auraColors(dark, accent, style, if (style == StyleChoice.SKY) SkyPalette.at(hour, dark) else null)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        if (activity != null) {
            SideEffect {
                runCatching {
                    val controller = WindowCompat.getInsetsController(activity.window, view)
                    // On the sky, the status bar sits on the sky's top and the nav bar on its pale
                    // bottom, so their icons follow the sky rather than the mode.
                    controller.isAppearanceLightStatusBars = colors.sky?.darkText ?: !dark
                    controller.isAppearanceLightNavigationBars = colors.sky != null || !dark
                }
            }
        }
    }

    CompositionLocalProvider(LocalAura provides colors) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = Typography, content = content)
    }
}

/** How often the sky re-reads the clock. */
private const val SKY_TICK_MS = 5 * 60 * 1000L

private fun currentHour(): Double = java.time.LocalTime.now().let { it.hour + it.minute / 60.0 }

private fun AuraColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = label,
        secondary = label,
        onSecondary = canvas,
        secondaryContainer = well,
        onSecondaryContainer = label,
        tertiary = label,
        onTertiary = canvas,
        tertiaryContainer = well,
        onTertiaryContainer = label,
        background = canvas,
        onBackground = label,
        surface = surfaceStrong,
        onSurface = label,
        surfaceVariant = well,
        onSurfaceVariant = secondary,
        surfaceTint = canvas,
        surfaceBright = surfaceStrong,
        surfaceDim = canvas,
        surfaceContainerLowest = surfaceStrong,
        surfaceContainerLow = surfaceStrong,
        surfaceContainer = surfaceStrong,
        surfaceContainerHigh = surfaceStrong,
        surfaceContainerHighest = surfaceStrong,
        inverseSurface = label,
        inverseOnSurface = canvas,
        outline = hairline,
        outlineVariant = hairline,
        error = danger,
        onError = if (isDark) canvas else surface,
        errorContainer = danger.copy(alpha = 0.14f),
        onErrorContainer = label,
        scrim = canvas.copy(alpha = 0.5f),
    )
}
