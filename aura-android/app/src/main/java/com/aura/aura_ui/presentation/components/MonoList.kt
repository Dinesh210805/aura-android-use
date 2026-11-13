package com.aura.aura_ui.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.MonoScheme
import com.aura.aura_ui.ui.theme.auraCard
import com.aura.aura_ui.ui.theme.toMono

/**
 * The grouped-list API older screens are written against; each piece draws with [AuraKit].
 *
 * - Contract: [MonoScheme] parameters are kept so call sites compile; colours come from
 *   [LocalAura]. New code calls the `Aura*` composables directly.
 * - Change together: `AuraKit.kt`.
 */
@Composable
fun rememberMonoScheme(): MonoScheme = LocalAura.current.toMono()

/** A top bar for screens that lay out their own page: round back button, centered title. */
@Composable
fun MonoTopBar(
    title: String,
    scheme: MonoScheme,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val aura = LocalAura.current
    val back = onBack ?: LocalBack.current
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) {
            if (back != null) CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", back)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = AuraType.headline, color = aura.label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (subtitle != null) Text(subtitle, style = AuraType.caption, color = aura.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** The label above a card group. */
@Composable
fun MonoSectionLabel(text: String, scheme: MonoScheme, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp), style = AuraType.headline, color = LocalAura.current.label)
}

/** A bordered card holding rows separated by hairlines. */
@Composable
fun MonoCardGroup(
    scheme: MonoScheme,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeMarker.() -> Unit,
) {
    Column(modifier.fillMaxWidth().auraCard()) { ColumnScopeMarker(scheme).content() }
}

/** Scope carrier so rows inside a group can draw their own dividers. */
class ColumnScopeMarker(val scheme: MonoScheme)

/** The line between two rows, inset past the icon. */
@Composable
fun ColumnScopeMarker.MonoDivider() = AuraDivider(inset = 52.dp)

/** One row: icon · title (and subtitle) · trailing, a chevron by default when clickable. */
@Composable
fun ColumnScopeMarker.MonoRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) = AuraRow(title, modifier, icon = icon, subtitle = subtitle, destructive = destructive, onClick = onClick, trailing = trailing)

/** The app switch. Null [onCheckedChange] is read-only. */
@Composable
fun MonoSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, scheme: MonoScheme) = AuraSwitch(checked, onCheckedChange)

/** A compact segmented control. */
@Composable
fun MonoSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    scheme: MonoScheme,
    modifier: Modifier = Modifier,
) = AuraSegmented(
    options = options.indices.toList(),
    selected = selectedIndex,
    label = { options[it] },
    onSelect = onSelect,
    modifier = modifier.width((options.size * 84).dp),
)
