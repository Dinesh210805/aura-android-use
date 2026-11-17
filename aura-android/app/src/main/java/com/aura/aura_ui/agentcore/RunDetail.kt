package com.aura.aura_ui.agentcore

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aura.aura_ui.presentation.components.AuraEmpty
import com.aura.aura_ui.presentation.components.AuraPage
import com.aura.aura_ui.presentation.components.AuraSegmented
import com.aura.aura_ui.presentation.components.CircleButton
import com.aura.aura_ui.presentation.screens.trace.JsonPalette
import com.aura.aura_ui.presentation.screens.trace.highlightJson
import com.aura.aura_ui.presentation.screens.trace.humanizePayload
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.auraCard
import com.aura.aura_ui.ui.theme.toMono
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * One run, in full: the request and outcome, its numbers, AURA's answer, then every step on a
 * numbered timeline (what the model decided, each tool call with what was sent and what came
 * back, the screen with the pressed element marked) and the system prompt.
 *
 * - Contract: raw values are shown as stored, pretty-printed and coloured but never shortened.
 *   "Copy for Claude Code" copies the raw values as Markdown, not the formatted ones, so a bug
 *   report carries exactly what the model saw.
 * - Change together: `RunJournal.kt` (the detail keys: "tool", "sent", "got back", "error",
 *   "model output", "input tokens", "output tokens", "finish").
 */
@Composable
fun RunDetailPage(id: String, onDeleted: () -> Unit, vm: AgentPagesViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val loaded by produceState<RunDetail?>(null, id) { value = vm.runDetail(id) }
    var filter by rememberSaveable { mutableStateOf(StepFilter.ALL) }
    var confirm by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val run = loaded?.run

    AuraPage("Run", scrollable = false, actions = {
        if (run != null) {
            CircleButton(Icons.Outlined.ContentCopy, "Copy for Claude Code", {
                clipboard.setText(AnnotatedString(runMarkdown(run, loaded?.system)))
                // Android 13+ shows its own clipboard confirmation.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied the run as Markdown", Toast.LENGTH_SHORT).show()
            })
            CircleButton(Icons.Outlined.IosShare, "Export this run's raw logs", {
                scope.launch { shareLogZip(context, vm.exportLog(setOf(id))) }
            })
            CircleButton(Icons.Outlined.Delete, "Delete this run", { confirm = true })
        }
    }) { padding ->
        when {
            loaded == null -> Unit
            run == null -> Box(Modifier.fillMaxSize()) { AuraEmpty("Run not found", "It was deleted, or is older than your log setting.") }
            else -> {
                val steps = run.steps.filter(filter::keeps)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "summary") { Summary(run) }
                    run.answer?.takeIf(String::isNotBlank)?.let { answer ->
                        item(key = "answer") {
                            Card {
                                Caption("AURA's answer")
                                RichText(answer, AuraType.callout, LocalAura.current.label)
                            }
                        }
                    }
                    item(key = "filter") {
                        Box(Modifier.fillMaxWidth().auraCard().padding(6.dp)) {
                            AuraSegmented(StepFilter.entries, filter, { it.title }, { filter = it })
                        }
                    }
                    if (filter == StepFilter.SCREENS) {
                        itemsIndexed(run.screens, key = { _, file -> "file-${file.id}" }) { _, file ->
                            ScreenFileCard(file, run.startMs) { viewing = it }
                        }
                        if (run.screens.isEmpty()) item(key = "none") { Card { Text("No screens were recorded in this run.", style = AuraType.callout, color = LocalAura.current.secondary) } }
                    } else {
                        itemsIndexed(steps, key = { i, step -> "${step.atMs}-$i" }) { _, step ->
                            StepCard(run.steps.indexOf(step) + 1, step, run.startMs)
                        }
                        if (steps.isEmpty()) item(key = "none") { Card { Text("No steps of this kind.", style = AuraType.callout, color = LocalAura.current.secondary) } }
                    }
                    loaded?.system?.let { prompt -> item(key = "prompt") { Fold("What the model was told") { Payload("system prompt", prompt) } } }
                }
            }
        }
    }

    val screens = run?.screens?.flatMap { it.versions }?.sortedBy { it.atMs }.orEmpty()
    viewing?.let { start ->
        ScreenViewer(screens, screens.indexOfFirst { it.id == start }.coerceAtLeast(0)) { viewing = null }
    }

    if (confirm) {
        ConfirmDelete("Delete this run?", "Its steps, screenshots and model calls are removed from this phone.", { confirm = false }) {
            confirm = false
            scope.launch {
                vm.deleteRuns(setOf(id))
                onDeleted()
            }
        }
    }
}

/** A run with the system prompt from its first model call, for the page. */
data class RunDetail(val run: RunLog?, val system: String?)

/** Which steps the timeline shows. */
enum class StepFilter(val title: String) {
    ALL("All"),
    ACTIONS("Actions"),
    MODEL("Model"),
    FAILED("Failed"),

    /** Not a step filter: the page shows the run's screens as files instead of its steps. */
    SCREENS("Screens");

    fun keeps(step: LogStep): Boolean = when (this) {
        ALL -> true
        ACTIONS -> step.kind == LogKind.ACTION || step.kind == LogKind.LOOK || step.kind == LogKind.ASK || step.kind == LogKind.SEEN
        MODEL -> step.kind == LogKind.MODEL || step.kind == LogKind.SAY || step.kind == LogKind.NOTE
        FAILED -> !step.ok
        SCREENS -> false
    }
}

@Composable
private fun Summary(run: RunLog) {
    val aura = LocalAura.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutcomePill(run.end)
            Spacer(Modifier.weight(1f))
            Text(runTime.format(Instant.ofEpochMilli(run.startMs)), style = AuraType.footnote, color = aura.tertiary)
        }
        SelectionContainer { Text(run.request.ifBlank { "A task" }, style = AuraType.title, color = aura.label) }
        Text(run.model, style = AuraType.code, color = aura.secondary)
        val input = run.steps.sumOf { it.detail("input tokens")?.toLongOrNull() ?: 0 }
        val output = run.steps.sumOf { it.detail("output tokens")?.toLongOrNull() ?: 0 }
        Column(Modifier.fillMaxWidth().clip(AuraShape.Inner).background(aura.well).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Time", duration(run.durationMs))
                Stat("Actions", run.actions.toString())
                Stat("Model calls", run.modelCalls.toString())
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Tokens in", compact(input))
                Stat("Tokens out", compact(output))
                Stat("Failed", run.failedSteps.toString(), alert = run.failedSteps > 0)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(name: String, value: String, alert: Boolean = false) {
    val aura = LocalAura.current
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = AuraType.title, color = if (alert) aura.danger else aura.label, maxLines = 1)
        Text(name, style = AuraType.caption, color = aura.secondary, maxLines = 1)
    }
}

/** A screen the model was never shown reads as background, not as something the agent looked at. */
private const val DIM_ALPHA = 0.6f

/** How the screen viewer shows a screen; Picture falls back to Text when no frame was recorded. */
private enum class ViewMode(val title: String) { PICTURE("Picture"), TEXT("Text"), DIFF("Diff") }

/** A version's picture in its file card; the viewer shows it full width. */
private val THUMB_WIDTH = 96.dp

/** Lines of a screen shown in its file card before the viewer is opened. */
private const val PREVIEW_LINES = 12

/** The agent's grid is about 280 columns wide; small and scrollable beats wrapped and unreadable. */
private val GRID_FONT = 9.sp

/**
 * One step: its number and kind, the sentence with the quoted parts in bold, the time since the
 * run started and how long it took, the screen, and the raw values. A failed step opens with its
 * values showing, so the error is the first thing read.
 */
@Composable
private fun StepCard(number: Int, step: LogStep, runStartMs: Long) {
    val aura = LocalAura.current
    var open by rememberSaveable(step.atMs, number) { mutableStateOf(!step.ok) }
    val tint = if (step.ok) aura.label else aura.danger
    Column(
        Modifier.fillMaxWidth().auraCard().animateContentSize()
            .then(if (step.dim) Modifier.alpha(DIM_ALPHA) else Modifier)
            .then(if (step.details.isNotEmpty()) Modifier.clickable(onClickLabel = if (open) "Hide values" else "Show values") { open = !open } else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(tint.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Text("$number", style = AuraType.caption, color = tint)
            }
            Spacer(Modifier.width(8.dp))
            Icon(iconFor(step.kind), contentDescription = null, tint = aura.secondary, modifier = Modifier.size(16.dp))
            Text(kindName(step), Modifier.padding(start = 6.dp), style = AuraType.caption.copy(fontWeight = FontWeight.SemiBold), color = aura.secondary)
            step.detail("tool")?.let { tool ->
                Text(
                    tool,
                    Modifier.padding(start = 8.dp).clip(AuraShape.Pill).background(aura.well).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = AuraType.code,
                    color = aura.label,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "+${duration(step.atMs - runStartMs)}" + if (step.durationMs > 0) " · ${duration(step.durationMs)}" else "",
                style = AuraType.footnote,
                color = aura.tertiary,
                maxLines = 1,
            )
        }
        Text(emphasise(step.title), style = if (step.kind == LogKind.SAY) AuraType.body else AuraType.callout, color = tint)
        if (!step.ok) {
            Text(
                "Did not work" + (step.detail("error")?.let { ": " + it.lineSequence().first().take(140) } ?: ""),
                Modifier.fillMaxWidth().clip(AuraShape.Inner).background(aura.danger.copy(alpha = 0.1f)).padding(10.dp),
                style = AuraType.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = aura.danger,
            )
        }
        step.image?.let { Shot(it, step.box, step.title, width = 168.dp) }
        if (step.details.isNotEmpty()) {
            if (open) {
                step.details.filter { it.first != "tool" }.forEach { (name, value) -> Payload(name, value) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show values", style = AuraType.footnote, color = aura.secondary)
                    FoldArrow(false, aura.secondary)
                }
            }
        }
    }
}

private fun LogStep.detail(name: String): String? = details.firstOrNull { it.first == name }?.second

private fun kindName(step: LogStep): String = when (step.kind) {
    LogKind.ACTION -> "ACTION"
    LogKind.LOOK -> "READ"
    LogKind.SAY -> "SAID"
    LogKind.ASK -> "ASKED YOU"
    LogKind.SCREEN -> "SCREEN SENT"
    LogKind.SEEN -> "SAW"
    LogKind.MODEL -> "MODEL"
    LogKind.NOTE -> "AURA"
}

/** Bold for the quoted parts of a step sentence: the thing pressed, typed or searched for. */
private fun emphasise(title: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    Regex("“[^”]*”").findAll(title).forEach { match ->
        append(title.substring(at, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.value) }
        at = match.range.last + 1
    }
    append(title.substring(at))
}

/**
 * One raw value: its name, then the value pretty-printed with JSON coloured. Selectable, so a
 * part can be copied.
 */
@Composable
private fun Payload(name: String, value: String) {
    val aura = LocalAura.current
    val palette = remember(aura) { JsonPalette.of(aura.toMono()) }
    val text = remember(value, palette) {
        if (name == RunLogBuilder.SCREEN_DETAIL) tintChanges(value, aura.success, aura.danger) else highlightJson(humanizePayload(value), palette, aura.label)
    }
    val error = name == "error"
    Column(
        Modifier.fillMaxWidth().clip(AuraShape.Inner).background(if (error) aura.danger.copy(alpha = 0.08f) else aura.well).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name.uppercase(), style = AuraType.caption.copy(fontWeight = FontWeight.SemiBold), color = if (error) aura.danger else aura.secondary)
            Spacer(Modifier.weight(1f))
            Text("${"%,d".format(value.length)} characters", style = AuraType.caption, color = aura.tertiary)
        }
        SelectionContainer { Text(text, style = AuraType.code, color = aura.label) }
    }
}

/**
 * A screen's text with what changed in colour: `+` lines and the rows under "new or changed:" in
 * [added], `-` lines and the "gone:" line in [removed]. Covers both the server's changes-only
 * screen and `screen_diff` output.
 */
internal fun tintChanges(text: String, added: Color, removed: Color): AnnotatedString = buildAnnotatedString {
    var inChanged = false
    text.lines().forEachIndexed { i, line ->
        if (i > 0) append('\n')
        when {
            line.startsWith("new or changed:") -> inChanged = true
            line.startsWith("gone:") || line.startsWith("unchanged:") || line.isBlank() -> inChanged = false
        }
        val colour = when {
            line.startsWith("+ ") || (inChanged && !line.startsWith("new or changed:") && !line.startsWith("som ")) -> added
            line.startsWith("- ") || line.startsWith("gone:") -> removed
            else -> null
        }
        if (colour == null) append(line) else withStyle(SpanStyle(color = colour)) { append(line) }
    }
}

/**
 * One screen file: its first id and caption, how many times the run was on it, and each version
 * with what changed since the one before in colour. A version opens the full-screen viewer.
 */
@Composable
private fun ScreenFileCard(file: ScreenFile, runStartMs: Long, onOpen: (String) -> Unit) {
    val aura = LocalAura.current
    var open by rememberSaveable(file.id) { mutableStateOf(false) }
    val unread = file.versions.none { it.byModel }
    Column(Modifier.fillMaxWidth().auraCard().animateContentSize().then(if (unread) Modifier.alpha(DIM_ALPHA) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${file.id} · ${file.caption}", style = AuraType.callout, color = aura.label, maxLines = 2)
                val count = file.versions.size
                Text(
                    (if (count == 1) "1 version" else "$count versions") + if (unread) " · not shown to the model" else "",
                    style = AuraType.footnote,
                    color = aura.tertiary,
                )
            }
            FoldArrow(open, aura.secondary)
        }
        if (open) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                file.versions.forEachIndexed { i, v -> VersionRow(v, file.versions.getOrNull(i - 1)?.text, runStartMs, onOpen) }
            }
        }
    }
}

/** One version in a file card: its id and time, then its first lines, or what changed since [before]. */
@Composable
private fun VersionRow(v: ScreenVersion, before: String?, runStartMs: Long, onOpen: (String) -> Unit) {
    val aura = LocalAura.current
    val picture = remember(v.frame) { ScreenPicture.parse(v.frame) }
    Column(
        Modifier.fillMaxWidth().clip(AuraShape.Inner).background(aura.well).clickable { onOpen(v.id) }.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${v.id} · +${duration(v.atMs - runStartMs)}" + (v.sameAs?.let { " · same as $it" } ?: "") + if (v.byModel) "" else " · not shown to the model",
            style = AuraType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = aura.secondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            picture?.let { ScreenPictureView(it, Modifier.width(THUMB_WIDTH)) }
            Text(screenBody(v, before, diff = before != null, aura.success, aura.danger), Modifier.weight(1f), style = AuraType.code, color = aura.label, maxLines = PREVIEW_LINES)
        }
    }
}

/** A version's text, or with [diff] what changed since [before] with + green and - red. */
private fun screenBody(v: ScreenVersion, before: String?, diff: Boolean, added: Color, removed: Color): AnnotatedString {
    val text = v.text ?: return AnnotatedString(RunJournal.NOT_RECORDED)
    if (!diff || before == null) return AnnotatedString(text)
    val lines = screenLineDiff(before.lines(), text.lines()).ifEmpty { listOf("No change from the screen before.") }
    return tintChanges(lines.joinToString("\n"), added, removed)
}

/**
 * Every screen of the run, one page each, swiped in time order: the screen's grid as the agent
 * reads it, drawn when opened (nothing is stored as a picture). "Diff" colours what changed since
 * the page before.
 */
@Composable
private fun ScreenViewer(screens: List<ScreenVersion>, start: Int, onClose: () -> Unit) {
    val aura = LocalAura.current
    val pager = rememberPagerState(initialPage = start) { screens.size }
    var mode by rememberSaveable { mutableStateOf(ViewMode.PICTURE) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(aura.surfaceStrong)) {
            val current = screens.getOrNull(pager.currentPage)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleButton(Icons.Outlined.Close, "Close", onClose)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("${current?.id.orEmpty()} · ${pager.currentPage + 1} of ${screens.size}", style = AuraType.headline, color = aura.label)
                    Text(current?.caption.orEmpty(), style = AuraType.footnote, color = aura.secondary, maxLines = 1)
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                AuraSegmented(ViewMode.entries, mode, { it.title }, { mode = it })
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val v = screens[page]
                val picture = remember(v.frame) { ScreenPicture.parse(v.frame) }
                if (mode == ViewMode.PICTURE && picture != null) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        ScreenPictureView(picture)
                    }
                } else {
                    val body = screenBody(v, screens.getOrNull(page - 1)?.text, mode == ViewMode.DIFF, aura.success, aura.danger)
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(body, style = AuraType.code.copy(fontSize = GRID_FONT), color = aura.label, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun Fold(title: String, content: @Composable ColumnScope.() -> Unit) {
    val aura = LocalAura.current
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().auraCard().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = AuraType.headline, color = aura.label)
            FoldArrow(open, aura.secondary)
        }
        if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), content = content)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().auraCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun Caption(text: String) {
    Text(text.uppercase(), style = AuraType.caption.copy(fontWeight = FontWeight.SemiBold), color = LocalAura.current.secondary)
}

/** A delete confirmation in the app's type. */
@Composable
internal fun ConfirmDelete(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val aura = LocalAura.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = aura.surfaceStrong,
        title = { Text(title, style = AuraType.title, color = aura.label) },
        text = { Text(message, style = AuraType.callout, color = aura.secondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", style = AuraType.headline, color = aura.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = AuraType.headline, color = aura.label) } },
    )
}

/**
 * The run as Markdown for a coding agent: the facts, then each step with its raw values in code
 * fences, then the system prompt. Pure.
 */
fun runMarkdown(run: RunLog, system: String?): String = buildString {
    appendLine("# AURA run ${run.id}")
    appendLine()
    appendLine("- Request: ${run.request}")
    appendLine("- Outcome: ${run.end?.name ?: "UNFINISHED"}")
    appendLine("- Model: ${run.model}")
    appendLine("- Started: ${Instant.ofEpochMilli(run.startMs)}; took ${run.durationMs} ms")
    appendLine("- Actions: ${run.actions}; model calls: ${run.modelCalls}; tokens: ${run.tokens}; failed steps: ${run.failedSteps}")
    run.answer?.let { appendLine("- Answer: $it") }
    appendLine()
    appendLine("## Steps")
    run.steps.forEachIndexed { i, step ->
        appendLine()
        appendLine("### ${i + 1}. [${step.kind}${if (step.ok) "" else ", FAILED"}] ${step.title}")
        appendLine("+${step.atMs - run.startMs} ms, took ${step.durationMs} ms" + (step.image?.let { ", screenshot ${it.name}" } ?: ""))
        step.box?.let { appendLine("pressed box (l,t,r,b fractions): $it") }
        step.details.forEach { (name, value) ->
            appendLine("$name:")
            appendLine("```")
            appendLine(value)
            appendLine("```")
        }
    }
    system?.let {
        appendLine()
        appendLine("## System prompt")
        appendLine("```")
        appendLine(it)
        appendLine("```")
    }
}
