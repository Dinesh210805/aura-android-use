package com.aura.aura_ui.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.aura.aura_ui.ui.theme.Capsule
import com.aura.aura_ui.ui.theme.CapsuleScheme
import com.aura.aura_ui.ui.theme.charBrush

// ============================================================================
// CAPSULE COMPONENTS — the dark-card side of the Capsule design language.
// White sheets come from the Mono list kit; these are the charcoal objects
// that sit among them (the "dark + light combined" rule).
// ============================================================================

/**
 * A charcoal gradient card — the Capsule design's dark object. Use ONE per
 * screen region as the focal element (hero, danger zone, commitment), never
 * as the default card.
 */
@Composable
fun CharcoalCard(
    cs: CapsuleScheme,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = if (pressed) 0.98f else 1f
    val body: @Composable () -> Unit = {
        Box(modifier = Modifier.background(cs.charBrush())) {
            androidx.compose.foundation.layout.Column(content = content)
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = Capsule.ShapeHero,
            color = Color.Transparent,
            interactionSource = interaction,
            modifier = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale },
        ) { body() }
    } else {
        Surface(
            shape = Capsule.ShapeHero,
            color = Color.Transparent,
            modifier = modifier.fillMaxWidth(),
        ) { body() }
    }
}
