package com.aura.aura_ui.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Monochrome Premium Dark Color Scheme
 * True blacks, subtle grays, white accents
 */
private val AppleDarkColorScheme = darkColorScheme(
    // Primary — White on black
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color.White,
    
    // Secondary — Silver
    secondary = Color(0xFFA0A0A0),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFBBBBBB),
    
    // Tertiary — Medium gray
    tertiary = Color(0xFF808080),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFF909090),
    
    // Surfaces — True black with subtle elevations
    surface = AppleColors.Dark.Surface,
    surfaceVariant = AppleColors.Dark.SurfaceSecondary,
    onSurface = AppleColors.LabelDark.Primary,
    onSurfaceVariant = AppleColors.LabelDark.Secondary,
    
    // Background — Pure black
    background = AppleColors.Dark.Background,
    onBackground = AppleColors.LabelDark.Primary,
    
    // Outline and borders
    outline = AppleColors.Dark.Separator,
    outlineVariant = AppleColors.Dark.SeparatorOpaque,
    
    // Inverse colors
    inverseSurface = AppleColors.Light.Surface,
    inverseOnSurface = AppleColors.LabelLight.Primary,
    inversePrimary = Color(0xFF404040),
    
    // Error states — Dark gray
    error = Color(0xFF606060),
    onError = Color.White,
    errorContainer = Color(0xFF3A3A3A),
    onErrorContainer = Color(0xFF909090),
    
    // Scrim for overlays
    scrim = Color.Black.copy(alpha = 0.6f),
    
    // Surface tint
    surfaceTint = Color(0xFF808080),
)

/**
 * Monochrome Premium Light Color Scheme
 * Clean whites, subtle grays, black accents
 */
private val AppleLightColorScheme = lightColorScheme(
    // Primary — Black on white
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color.Black,
    
    // Secondary — Dark gray
    secondary = Color(0xFF6B6B6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF4A4A4A),
    
    // Tertiary — Medium gray
    tertiary = Color(0xFF8F8F8F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDEDED),
    onTertiaryContainer = Color(0xFF6B6B6B),
    
    // Surfaces — Clean whites
    surface = AppleColors.Light.Surface,
    surfaceVariant = AppleColors.Light.SurfaceSecondary,
    onSurface = AppleColors.LabelLight.Primary,
    onSurfaceVariant = AppleColors.LabelLight.Secondary,
    
    // Background — Subtle gray
    background = AppleColors.Light.Background,
    onBackground = AppleColors.LabelLight.Primary,
    
    // Outline and borders
    outline = AppleColors.Light.Separator,
    outlineVariant = AppleColors.Light.SeparatorOpaque,
    
    // Inverse colors
    inverseSurface = AppleColors.Dark.Surface,
    inverseOnSurface = AppleColors.LabelDark.Primary,
    inversePrimary = Color(0xFFD4D4D4),
    
    // Error states — Dark gray
    error = Color(0xFF505050),
    onError = Color.White,
    errorContainer = Color(0xFFE8E8E8),
    onErrorContainer = Color(0xFF3A3A3A),
    
    // Scrim for overlays
    scrim = Color.Black.copy(alpha = 0.35f),
    
    // Surface tint
    surfaceTint = Color(0xFF808080),
)

@Composable
fun AuraUITheme(
    // Dark mode was removed as a user-facing app setting — every app surface is
    // light. The overlay is the ONE exception: it passes darkTheme = true to
    // render permanently on the app's charcoal palette. So this honors its
    // parameter directly instead of reading a (now-removed) global theme mode.
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = darkTheme

    val colorScheme = if (useDarkTheme) AppleDarkColorScheme else AppleLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        // Only apply status bar styling for Activity contexts
        // Overlay services don't have windows that can be styled this way
        val context = view.context
        val activity = context as? Activity
        if (activity != null) {
            SideEffect {
                try {
                    val window = activity.window
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !useDarkTheme
                    insetsController.isAppearanceLightNavigationBars = !useDarkTheme
                } catch (e: Exception) {
                    // Ignore - may fail for non-standard Activity types
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
