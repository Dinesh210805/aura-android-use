package com.aura.aura_ui.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.ui.theme.LocalAura

/**
 * The AURA app icon as a badge: the mark on a filled circle, black with a white mark in light
 * theme and inverted in dark, like the launcher icon and the website favicon.
 *
 * @param activeRun badges a dot at the top right while a run is live.
 */
@Composable
fun AuraLogoBubble(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    activeRun: Boolean = false,
) {
    val aura = LocalAura.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(aura.label), contentAlignment = Alignment.Center) {
            AuraMark(size = size * 0.56f, tint = aura.canvas)
        }
        if (activeRun) {
            Box(
                Modifier.align(Alignment.TopEnd).size(size * 0.16f).clip(CircleShape).background(aura.canvas),
                contentAlignment = Alignment.Center,
            ) {
                BloodDot(size = size * 0.10f)
            }
        }
    }
}
