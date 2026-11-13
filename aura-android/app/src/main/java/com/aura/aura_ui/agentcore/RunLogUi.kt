package com.aura.aura_ui.agentcore

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.auraCard
import com.aura.aura_ui.ui.theme.rememberBitmap
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The run list: one card per task AURA ran, with its outcome, request and counts. Tapping a card
 * opens the run's page (`RunDetail.kt`); a long press starts selecting runs to delete.
 *
 * - Contract: draws [RunLog] values and nothing else; reading the files is the caller's job
 *   (`AgentTelemetry.recent`). With [selected] non-empty, a tap toggles a card instead of opening it.
 * - Change together: `RunJournal.kt` (what a [LogStep] can hold), `RunDetail.kt`.
 */
@Composable
fun RunLogList(
    runs: List<RunLog>,
    selected: Set<String>,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: LazyListScope.() -> Unit = {},
) {
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        header()
        items(runs, key = { it.id }) { run ->
            RunCard(run, selecting = selected.isNotEmpty(), selected = run.id in selected, onOpen = { onOpen(run.id) }, onToggle = { onToggle(run.id) })
        }
    }
}

internal val runTime: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunCard(run: RunLog, selecting: Boolean, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val aura = LocalAura.current
    Row(
        Modifier.fillMaxWidth().auraCard()
            .combinedClickable(onClickLabel = if (selecting) "Select run" else "Open run", onLongClickLabel = "Select run", onLongClick = onToggle, onClick = if (selecting) onToggle else onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectMark(selected)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutcomePill(run.end)
                Spacer(Modifier.weight(1f))
                Text(runTime.format(Instant.ofEpochMilli(run.startMs)), style = AuraType.footnote, color = aura.tertiary)
            }
            Text(run.request.ifBlank { "A task" }, style = AuraType.headline, color = aura.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(runFacts(run), style = AuraType.footnote, color = aura.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!selecting) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = aura.tertiary, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

/** "12s · 4 actions · 5.2k tokens", with failures in red. */
@Composable
internal fun runFacts(run: RunLog): AnnotatedString {
    val aura = LocalAura.current
    return buildAnnotatedString {
        append(listOf(duration(run.durationMs), plural(run.actions, "action"), "${compact(run.tokens)} tokens").joinToString(" · "))
        if (run.failedSteps > 0) {
            append(" · ")
            withStyle(SpanStyle(color = aura.danger, fontWeight = FontWeight.SemiBold)) { append("${run.failedSteps} failed") }
        }
    }
}

/** The round check of a selectable row. */
@Composable
internal fun SelectMark(selected: Boolean) {
    val aura = LocalAura.current
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(aura.accent) else Modifier.border(1.5.dp, aura.tertiary, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = aura.onAccent, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun OutcomePill(end: TurnEnd?) {
    val aura = LocalAura.current
    val (text, tint) = when (end) {
        TurnEnd.ANSWERED -> "Done" to aura.label
        TurnEnd.CANCELLED -> "Stopped" to aura.secondary
        TurnEnd.LIMIT -> "Ran out of time" to aura.warning
        TurnEnd.FAILED -> "Failed" to aura.danger
        TurnEnd.HANDED_OFF, null -> "Did not finish" to aura.secondary
    }
    Row(Modifier.clip(AuraShape.Pill).background(tint.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(6.dp))
        Text(text, style = AuraType.caption, color = aura.label)
    }
}

internal fun iconFor(kind: LogKind): ImageVector = when (kind) {
    LogKind.ACTION -> Icons.Outlined.TouchApp
    LogKind.LOOK -> Icons.Outlined.Visibility
    LogKind.SCREEN -> Icons.Outlined.Image
    LogKind.ASK -> Icons.Outlined.HelpOutline
    LogKind.SAY -> Icons.Outlined.ChatBubbleOutline
    LogKind.MODEL -> Icons.Outlined.AutoAwesome
    LogKind.NOTE -> Icons.Outlined.Info
}

/** A screenshot thumbnail that opens full screen. [box] marks the pressed element on both. */
@Composable
internal fun Shot(file: File, box: List<Float>?, caption: String, width: Dp = 132.dp) {
    val aura = LocalAura.current
    val picture by rememberBitmap(file.path) { BitmapFactory.decodeFile(file.path) }
    var full by remember(file.path) { mutableStateOf(false) }
    val bitmap = picture ?: return
    Box(
        Modifier.width(width).clip(AuraShape.Inner).background(aura.well)
            .clickable(onClickLabel = "Open the screenshot", role = Role.Image) { full = true },
    ) {
        MarkedScreen(bitmap, box, caption)
    }
    if (full) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClickLabel = "Close") { full = false }.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.weight(1f, fill = false).clip(AuraShape.Inner)) { MarkedScreen(bitmap, box, caption) }
                Text(caption, Modifier.padding(top = 14.dp), style = AuraType.callout, color = Color.White)
            }
        }
    }
}

/**
 * The screenshot with the pressed element outlined.
 * - Contract: [box] is left, top, right, bottom as fractions of the picture, so the outline
 *   lands on the element at any drawn size. The box keeps the picture's aspect ratio.
 */
@Composable
private fun MarkedScreen(bitmap: ImageBitmap, box: List<Float>?, caption: String) {
    Box(Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
        Image(bitmap, contentDescription = "Screen: $caption", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        if (box != null && box.size == 4) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 3.dp.toPx()
                val topLeft = Offset(box[0] * size.width - pad, box[1] * size.height - pad)
                val area = Size((box[2] - box[0]) * size.width + 2 * pad, (box[3] - box[1]) * size.height + 2 * pad)
                val radius = CornerRadius(6.dp.toPx())
                drawRoundRect(MARK.copy(alpha = 0.22f), topLeft, area, radius)
                drawRoundRect(MARK, topLeft, area, radius, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** The outline on a pressed element. Why: the one place the monochrome app needs colour, so the mark reads on any screenshot. */
private val MARK = Color(0xFFFF9F0A)

internal fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        ms in 1..999 -> "${ms}ms"
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}

internal fun compact(n: Long): String = if (n < 1000) "$n" else "%.1fk".format(n / 1000.0)

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** The arrow used on rows that fold. */
@Composable
fun FoldArrow(open: Boolean, tint: Color) {
    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
}
