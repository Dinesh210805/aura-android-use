package com.aura.aura_ui.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================================
// AURA LOGO BUBBLE — the COLORED AURA mark on a white circular bubble.
//
// The brand-identity token for splash, onboarding, the Home hero and empty
// states. The white bubble keeps the multi-color mark legible on ANY canvas
// (light or dark), which the single-tint MonoLogoMark cannot do. This is the
// sanctioned place for the colored logo as brand (spec v2 §1.5) — distinct from
// the animated AuraLogoButton, still reserved for live/thinking in the overlay.
// ============================================================================

/**
 * @param size overall diameter of the bubble.
 * @param activeRun when true, badges a blood dot at the top-right (a run is live).
 */
@Composable
fun AuraLogoBubble(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    activeRun: Boolean = false,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 10.dp,
            modifier = Modifier.size(size),
        ) {}
        AuraLogoAvatar(size = size * 0.62f)
        if (activeRun) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(size * 0.16f)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                BloodDot(size = size * 0.10f)
            }
        }
    }
}
