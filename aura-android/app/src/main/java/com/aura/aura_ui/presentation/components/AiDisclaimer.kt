package com.aura.aura_ui.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// AI DISCLAIMER — Play-readiness honesty line. Reused on Home + onboarding;
// Home can opt into a smaller type size to keep the primary action prominent.
// ============================================================================

@Composable
fun AiDisclaimer(
    modifier: Modifier = Modifier,
    smallText: Boolean = false,
) {
    val scheme = rememberMonoScheme()
    Text(
        text = "AURA is powered by AI and may occasionally make mistakes. Please review important actions.",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = if (smallText) 10.sp else 11.sp),
        color = scheme.textSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    )
}
