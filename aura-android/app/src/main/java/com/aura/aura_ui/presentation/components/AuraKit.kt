package com.aura.aura_ui.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.R
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.LocalHaze
import com.aura.aura_ui.ui.theme.auraCard
import com.aura.aura_ui.ui.theme.auraBackground
import com.aura.aura_ui.ui.theme.glass
import com.aura.aura_ui.ui.theme.glassBar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import dev.chrisbanes.haze.hazeSource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import dev.chrisbanes.haze.rememberHazeState

/**
 * The building blocks of every page in the app: the page frame, grouped tables, rows, switches,
 * buttons and the AURA mark, in the ChatGPT and iOS settings style.
 *
 * - Contract: colours and type come from `ui/theme/AuraTheme.kt` only. A page never draws its
 *   own back arrow or title; [AuraPage] does, and its back action comes from [LocalBack].
 * - Change together: `MonoList.kt` and `AuraScreenScaffold.kt` delegate to these.
 */

/** Goes back one screen. The navigation shell provides it; null on the top screen. */
val LocalBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * A page: a round back button, a large title and the content, on the page background
 * ([auraBackground]: the sky or the flat canvas), edge to edge behind the system bars.
 *
 * - Contract: the header scrolls away with the content, as in MilkLogger, so nothing sits over
 *   the content and there is no bar to colour. Title text uses [AuraColors.onSky].
 *
 * @param onBack overrides [LocalBack]; pass it only when "back" means something else here.
 * @param scrollable false when [content] scrolls itself (a `LazyColumn`), which must then apply
 *   the [PaddingValues] it is given; the header then stays above the list.
 * @param orbits draws the slowly turning rings in the top right, for top-level pages.
 */
@Composable
fun AuraPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = LocalBack.current,
    scrollable: Boolean = true,
    orbits: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.(PaddingValues) -> Unit,
) {
    val haze = rememberHazeState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    CompositionLocalProvider(LocalHaze provides haze) {
        Box(modifier.fillMaxSize().hazeSource(haze).auraBackground()) {
            if (orbits) SkyOrbits()
            if (scrollable) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
                        .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 32.dp + navBottom),
                ) {
                    PageHeader(title, subtitle, onBack, actions)
                    content(PaddingValues(0.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Box(Modifier.padding(horizontal = PAGE_GUTTER)) { PageHeader(title, subtitle, onBack, actions) }
                    content(PaddingValues(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 4.dp, bottom = 32.dp + navBottom))
                }
            }
        }
    }
}

/** The back button, then the large title and its subtitle, on the page itself. */
@Composable
private fun PageHeader(title: String, subtitle: String?, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    val aura = LocalAura.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Text(title, Modifier.padding(start = 4.dp, top = 10.dp), style = AuraType.largeTitle, color = aura.onSky)
        if (subtitle != null) Text(subtitle, Modifier.padding(start = 4.dp, top = 2.dp), style = AuraType.callout, color = aura.onSkyMuted)
    }
}

/**
 * Three concentric rings and two small dots turning once every 48 seconds in the top right:
 * MilkLogger's orbits. Sky theme only; still when the system turns animations off.
 */
@Composable
fun BoxScope.SkyOrbits() {
    val aura = LocalAura.current
    val sky = aura.sky ?: return
    val line = sky.onSkyLine.copy(alpha = sky.onSkyLine.alpha * 0.7f)
    val animate = remember { android.animation.ValueAnimator.areAnimatorsEnabled() }
    val turn = if (animate) {
        val spin = rememberInfiniteTransition(label = "orbits")
        spin.animateFloat(0f, 360f, infiniteRepeatable(tween(48_000, easing = LinearEasing)), label = "turn").value
    } else {
        0f
    }
    Canvas(
        Modifier.align(Alignment.TopEnd).offset(x = 130.dp, y = (-120).dp).size(380.dp).graphicsLayer { rotationZ = turn },
    ) {
        val stroke = Stroke(width = 1.dp.toPx())
        listOf(74f, 118f, 164f).forEach { r -> drawCircle(line, radius = r.dp.toPx(), style = stroke) }
        val dot = line.copy(alpha = minOf(1f, line.alpha * 2.4f))
        drawCircle(dot, 3.5.dp.toPx(), center + Offset(0f, -118.dp.toPx()))
        drawCircle(dot, 2.5.dp.toPx(), center + Offset(kotlin.math.cos(0.6f) * 164.dp.toPx(), kotlin.math.sin(0.6f) * 164.dp.toPx()))
        drawCircle(dot, 2.dp.toPx(), center + Offset(kotlin.math.cos(2.6f) * 74.dp.toPx(), kotlin.math.sin(2.6f) * 74.dp.toPx()))
    }
}

/** Space between a page's edge and its cards. */
val PAGE_GUTTER: Dp = 16.dp

/** A round glass button with one icon, 44dp to touch. */
@Composable
fun CircleButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val aura = LocalAura.current
    Box(modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        // A solid glass fill, not a blur: the button sits inside the page's own blurred layer.
        Box(Modifier.size(42.dp).auraCard(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = aura.label, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A group of rows in one card. The [title] and [footer] sit inside the card, so they read on any
 * sky (MilkLogger's settings cards do the same).
 */
@Composable
fun AuraSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val aura = LocalAura.current
    Column(modifier.fillMaxWidth().padding(top = 14.dp).auraCard()) {
        if (title != null) Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), style = AuraType.headline, color = aura.label)
        content()
        if (footer != null) Text(footer, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp), style = AuraType.footnote, color = aura.secondary)
    }
}

/** The line between two rows of an [AuraSection]. */
@Composable
fun AuraDivider(inset: Dp = 0.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(LocalAura.current.hairline))
}

/**
 * One row of a table: icon · title (and subtitle) · value · chevron.
 * A chevron is drawn when the row opens something and has no [trailing] of its own.
 */
@Composable
fun AuraRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    destructive: Boolean = false,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val aura = LocalAura.current
    val color = tint ?: if (destructive) aura.danger else aura.label
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraType.body, color = color)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = AuraType.footnote, color = aura.secondary)
        }
        if (value != null) {
            Text(value, Modifier.padding(start = 12.dp).weight(1f, fill = false), style = AuraType.callout, color = aura.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            trailing != null -> Box(Modifier.padding(start = 8.dp)) { trailing() }
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = aura.tertiary, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
    }
}

/** A row with a switch; the whole row toggles. */
@Composable
fun AuraToggleRow(
    title: String,
    checked: Boolean,
    onChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    AuraRow(
        title = title,
        modifier = modifier,
        icon = icon,
        subtitle = subtitle,
        onClick = onChange?.let { change -> { change(!checked) } },
        trailing = { AuraSwitch(checked, onChange) },
    )
}

/** An iOS segmented control. */
@Composable
fun <T> AuraSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val aura = LocalAura.current
    Row(modifier.fillMaxWidth().clip(AuraShape.Pill).background(aura.well).padding(3.dp)) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(AuraShape.Pill)
                    .background(if (on) aura.surface else Color.Transparent)
                    .clickable(role = Role.Tab, onClickLabel = label(option)) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = AuraType.caption, color = if (on) aura.label else aura.secondary)
            }
        }
    }
}

/** A full-width button. [primary] is filled with the accent; otherwise a grey well. */
@Composable
fun AuraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    val aura = LocalAura.current
    val fill = when {
        destructive -> aura.danger.copy(alpha = 0.12f)
        primary -> aura.accent
        else -> aura.well
    }
    val ink = when {
        destructive -> aura.danger
        primary -> aura.onAccent
        else -> aura.label
    }
    Row(
        modifier.heightIn(min = 50.dp).clip(AuraShape.Pill).background(if (enabled) fill else fill.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = AuraType.headline, color = if (enabled) ink else ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A short line of secondary text, for empty states and explanations. */
@Composable
fun AuraHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = AuraType.footnote, color = LocalAura.current.secondary)
}

/** The AURA mark in one colour: black on light, white on dark unless [tint] says otherwise. */
@Composable
fun AuraMark(modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalAura.current.label) {
    Image(
        painter = painterResource(R.drawable.aura_mark),
        contentDescription = "AURA",
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** A centered empty state inside a page. */
@Composable
fun BoxScope.AuraEmpty(title: String, detail: String) {
    val aura = LocalAura.current
    Column(Modifier.align(Alignment.Center).padding(24.dp).auraCard().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = AuraType.headline, color = aura.label, textAlign = TextAlign.Center)
        Text(detail, Modifier.padding(top = 6.dp), style = AuraType.footnote, color = aura.secondary, textAlign = TextAlign.Center)
    }
}
