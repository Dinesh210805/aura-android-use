package com.aura.aura_ui.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.paper

/**
 * The page frame for screens built on the older palettes: [AuraPage]'s header, then the content
 * on one sheet of paper.
 *
 * - Contract: [onBack] null falls back to [LocalBack]. Why the sheet: these screens draw ink text
 *   straight on their page; in Sky the sheet is opaque paper so that text reads on any sky. In
 *   Classic the sheet is the canvas colour and does not show.
 */
@Composable
fun AuraScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheet = Modifier.clip(AuraShape.Card).background(LocalAura.current.paper).padding(horizontal = 12.dp, vertical = 8.dp)
    AuraPage(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        onBack = onBack ?: LocalBack.current,
        scrollable = scrollable,
    ) { inner ->
        if (scrollable) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).then(sheet), content = content)
        } else {
            Column(Modifier.fillMaxSize().padding(inner).then(sheet), content = content)
        }
    }
}
