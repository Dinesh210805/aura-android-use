package com.aura.aura_ui.presentation.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.aura.aura_ui.ui.theme.LocalAura

/**
 * The one switch in the app. On is the accent (ink in light, white in dark by default); off is a
 * grey track.
 *
 * - Contract: null [onCheckedChange] is read-only; its colours match the enabled ones so a
 *   locked-on switch still reads as on.
 */
@Composable
fun AuraSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val aura = LocalAura.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = aura.onAccent,
            checkedTrackColor = aura.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = aura.well,
            uncheckedBorderColor = aura.hairline,
            disabledCheckedThumbColor = aura.onAccent,
            disabledCheckedTrackColor = aura.accent.copy(alpha = 0.6f),
            disabledUncheckedThumbColor = Color.White,
            disabledUncheckedTrackColor = aura.well,
        ),
    )
}
