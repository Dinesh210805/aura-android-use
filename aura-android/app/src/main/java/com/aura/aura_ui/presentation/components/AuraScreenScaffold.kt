package com.aura.aura_ui.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.canvasBrush
import com.aura.aura_ui.ui.theme.rememberWarmScheme

// ============================================================================
// AURA SCREEN SCAFFOLD — the single container every screen sits in.
//
// Canvas background, status-bar-safe, an optional back arrow, a large title +
// optional subtitle, and one horizontal padding rhythm (Mono.ScreenH). This is
// the mechanism that keeps every page and subpage in the SAME style — screens
// bring only their content slot, never their own chrome (spec v2 §1.2).
// ============================================================================

@Composable
fun AuraScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = rememberMonoScheme()
    val warm = rememberWarmScheme()
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(warm.canvasBrush())
            .statusBarsPadding(),
    ) {
        // Capsule design (2026-07-10): back lives in the bottom capsule strip
        // (AuraAppShell's contextual mode), not as a top arrow — [onBack] is
        // kept in the signature so call sites stay untouched, but unused here.
        Spacer(Modifier.height(Mono.Space.lg))

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Mono.ScreenH)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                ),
                color = scheme.textPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.textSecondary)
            }
            Spacer(Modifier.height(Mono.Space.md))
        }

        val bodyModifier = if (scrollable) {
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = Mono.ScreenH)
        } else {
            Modifier.fillMaxSize().padding(horizontal = Mono.ScreenH)
        }
        Column(modifier = bodyModifier, content = content)
    }
}
