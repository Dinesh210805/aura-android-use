package com.aura.aura_ui.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraPage
import com.aura.aura_ui.presentation.components.AuraRow
import com.aura.aura_ui.presentation.components.AuraSection
import com.aura.aura_ui.ui.theme.AccentChoice
import com.aura.aura_ui.ui.theme.AppearanceStore
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.SkyPalette
import com.aura.aura_ui.ui.theme.StyleChoice
import com.aura.aura_ui.ui.theme.ThemeChoice

/**
 * Light or dark mode, the theme (Sky or Classic) and the accent colour.
 * - Reads/Writes: [AppearanceStore]; every screen restyles at once.
 */
@Composable
fun AppearanceScreen() {
    val context = LocalContext.current
    val aura = LocalAura.current
    val mode by AppearanceStore.theme.collectAsState()
    val style by AppearanceStore.style.collectAsState()
    val accent by AppearanceStore.accent.collectAsState()

    AuraPage("Appearance") {
        AuraSection(title = "Theme") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StyleChoice.entries.forEach { choice ->
                    ThemeTile(choice, choice == style, Modifier.weight(1f)) { AppearanceStore.setStyle(context, choice) }
                }
            }
        }
        AuraSection(title = "Mode") {
            ThemeChoice.entries.forEachIndexed { i, choice ->
                if (i > 0) AuraDivider(16.dp)
                AuraRow(choice.title, onClick = { AppearanceStore.setTheme(context, choice) }, trailing = {
                    if (choice == mode) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = aura.label, modifier = Modifier.size(20.dp))
                })
            }
        }
        AuraSection(title = "Accent colour") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                AccentChoice.entries.forEach { choice ->
                    val on = choice == accent
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(choice.color(aura.isDark))
                            .border(if (on) 3.dp else 1.dp, if (on) aura.label.copy(alpha = 0.35f) else aura.hairline, CircleShape)
                            .clickable(role = Role.RadioButton, onClickLabel = choice.title) { AppearanceStore.setAccent(context, choice) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Icon(Icons.Rounded.Check, contentDescription = "${choice.title}, selected", tint = choice.on(aura.isDark), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** A small preview of a theme: the sky for Sky, black and white for Classic. */
@Composable
private fun ThemeTile(choice: StyleChoice, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val aura = LocalAura.current
    val fill = when (choice) {
        StyleChoice.SKY -> Brush.verticalGradient(SkyPalette.at(12.0, dark = false).stops)
        StyleChoice.CLASSIC -> Brush.horizontalGradient(0.5f to Color(0xFFF9F9F9), 0.5f to Color.Black)
    }
    Column(modifier.clip(AuraShape.Inner).clickable(role = Role.RadioButton, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.8f).clip(AuraShape.Inner).background(fill)
                .border(if (selected) 2.dp else 1.dp, if (selected) aura.label else aura.hairline, AuraShape.Inner),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.padding(10.dp).fillMaxWidth().aspectRatio(2.6f).clip(AuraShape.Inner).background(Color.White.copy(alpha = 0.8f)))
        }
        Text(choice.title, Modifier.padding(top = 8.dp), style = AuraType.callout, color = aura.label)
    }
}
