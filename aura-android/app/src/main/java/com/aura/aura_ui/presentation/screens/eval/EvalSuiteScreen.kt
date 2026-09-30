package com.aura.aura_ui.presentation.screens.eval

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.eval.EvalCategory
import com.aura.aura_ui.eval.EvalController
import com.aura.aura_ui.eval.EvalRunner
import com.aura.aura_ui.eval.EvalStatus
import com.aura.aura_ui.eval.EvalTally
import com.aura.aura_ui.eval.EvalTask
import com.aura.aura_ui.eval.EvalTaskResult
import com.aura.aura_ui.eval.EvalVerdict
import com.aura.aura_ui.presentation.components.MonoTopBar
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Mono

/**
 * The eval cockpit — watch the suite run, score each task while you can still see the screen.
 *
 * ### What this replaces
 *
 * A PowerShell script that broadcast goals and scraped logcat, plus a markdown file the human typed
 * verdicts into on a laptop while trying to remember what the phone had shown ten minutes earlier.
 * Scoring is the part that has to be right — it is the only gate metric this project has — and it
 * was the part happening furthest from the evidence.
 *
 * ### The two things the design turns on
 *
 * **Nothing is scored automatically except a rate limit.** A finished run shows as *awaiting score*,
 * never as a pass. The agent's own claim of completion lied in production (2026-07-29: tapped
 * Library in Spotify, nothing played, reported success), so the only thing allowed to write a
 * verdict is a human who watched.
 *
 * **A quota refusal pauses instead of failing onward.** On a free tier there is no second attempt at
 * a suite, so losing a half-finished run loses the day. The banner tells the user to swap the key
 * and resume; every result already earned stays.
 *
 * Debug builds only — registered behind `BuildConfig.DEBUG` in `AuraAppShell`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvalSuiteScreen(
    onOpenTrace: (sessionId: String) -> Unit,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current

    LaunchedEffect(Unit) { EvalController.attach(context) }

    val tasks by EvalController.tasks.collectAsState()
    val runs by EvalController.runs.collectAsState()
    val state by EvalController.state.collectAsState()

    var skipNeedsHuman by remember { mutableStateOf(false) }
    var gateOnScoring by remember { mutableStateOf(EvalController.gateOnScoring) }
    var detailFor by remember { mutableStateOf<Int?>(null) }

    val byNumber = remember(tasks) { tasks.associateBy { it.number } }
    val tally = state.tally

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.canvas)
            .padding(horizontal = 16.dp),
    ) {
        MonoTopBar(
            title = "Eval suite",
            subtitle = state.runId ?: "No run open",
            scheme = scheme,
        )

        if (state.runId == null) {
            EvalRunPicker(
                runs = runs,
                auraCount = remember(tasks) { com.aura.aura_ui.eval.EvalTaskCatalog.auraSuite(tasks).size },
                androidWorldCount = remember(tasks) {
                    com.aura.aura_ui.eval.EvalTaskCatalog.androidWorldSuite(tasks).size
                },
                coverageGaps = remember(tasks) { com.aura.aura_ui.eval.EvalTaskCatalog.coverageGaps(tasks) },
                problems = remember(tasks) { com.aura.aura_ui.eval.EvalTaskCatalog.validate(tasks) },
                onCreate = { label, aw -> EvalController.createRun(context, label, aw) },
                onOpen = { EvalController.open(it) },
                onDelete = { EvalController.deleteRun(it) },
            )
            return@Column
        }

        // The halt banner is the single most important thing on this screen when it is showing:
        // it is the difference between "swap your key and carry on" and "you lost the day".
        state.haltReason?.let { reason ->
            HaltBanner(
                reason = reason,
                isRateLimit = state.haltedByRateLimit,
                isScoringGate = state.haltedForScoring,
            )
            Spacer(Modifier.height(12.dp))
        }

        EvalProgressHeader(tally = tally, phase = state.phase)
        Spacer(Modifier.height(12.dp))

        EvalControls(
            phase = state.phase,
            skipNeedsHuman = skipNeedsHuman,
            onToggleSkip = { skipNeedsHuman = it },
            gateOnScoring = gateOnScoring,
            onToggleGate = { gateOnScoring = it; EvalController.gateOnScoring = it },
            onRun = { EvalController.runAll(skipNeedsHuman) },
            onPause = { EvalController.pause() },
            onAbort = { EvalController.abort() },
            onExport = { EvalController.exportCurrent() },
            onExportAll = { EvalController.exportEverything() },
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(state.results, key = { it.number }) { result ->
                EvalTaskRow(
                    result = result,
                    task = byNumber[result.number],
                    isRunning = state.currentTaskNumber == result.number,
                    onClick = { detailFor = result.number },
                )
            }
        }
    }

    detailFor?.let { number ->
        val result = state.results.firstOrNull { it.number == number }
        if (result != null) {
            EvalTaskSheet(
                result = result,
                task = byNumber[number],
                canRun = state.phase != EvalRunner.Phase.RUNNING,
                onDismiss = { detailFor = null },
                onScore = { verdict, notes -> EvalController.score(number, verdict, notes) },
                onSaveNotes = { EvalController.saveNotes(number, it) },
                onClearScore = { EvalController.clearScore(number) },
                onRerun = { byNumber[number]?.let { EvalController.runOne(it) } },
                onOpenTrace = onOpenTrace,
            )
        }
    }
}

/**
 * The banner that appears when the runner stopped itself.
 *
 * Blood red is reserved for attention in this design language, and a quota halt is the one state
 * on this screen where the user must act before anything else can happen — every other stop is
 * something they chose.
 */
@Composable
private fun HaltBanner(reason: String, isRateLimit: Boolean, isScoringGate: Boolean = false) {
    val scheme = rememberMonoScheme()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Mono.ShapeCardSmall)
            .background(if (isRateLimit) scheme.bloodContainer else scheme.card)
            .border(
                1.dp,
                if (isRateLimit) Mono.Blood.copy(alpha = 0.4f) else scheme.outline,
                Mono.ShapeCardSmall,
            )
            .padding(14.dp),
    ) {
        Text(
            when {
                isRateLimit -> "Out of quota — paused"
                isScoringGate -> "Waiting for your verdict"
                else -> "Paused"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isRateLimit) Mono.Blood else scheme.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(reason, style = MaterialTheme.typography.bodySmall, color = scheme.textSecondary)
    }
}

@Composable
private fun EvalProgressHeader(tally: EvalTally, phase: EvalRunner.Phase) {
    val scheme = rememberMonoScheme()
    val done = tally.total - tally.remaining
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Mono.ShapeCard)
            .background(scheme.card)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$done / ${tally.total} scored",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                phase.name.lowercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (phase == EvalRunner.Phase.RUNNING) Mono.Blood else scheme.textSecondary,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (tally.total == 0) 0f else done / tally.total.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(Mono.ShapePill),
            color = scheme.textPrimary,
            trackColor = scheme.divider,
        )
        Spacer(Modifier.height(12.dp))

        // Verified success is deliberately the only large number, and it stays absent rather than
        // showing 0% before anything has been judged: an unscored run is not a measurement.
        Text(
            tally.verifiedSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "—",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = scheme.textPrimary,
        )
        Text(
            if (tally.verifiedSuccessRate == null) {
                "verified success — nothing judged yet"
            } else {
                "verified success (${tally.passed + tally.refused} of ${tally.judged} judged)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.textSecondary,
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TallyChip("${tally.passed} pass", scheme.chip, scheme.chipContent)
            TallyChip("${tally.failed} fail", scheme.chip, scheme.chipContent)
            if (tally.refused > 0) TallyChip("${tally.refused} refused", scheme.chip, scheme.chipContent)
            if (tally.invalid > 0) TallyChip("${tally.invalid} invalid", scheme.chip, scheme.chipContent)
            if (tally.errored > 0) TallyChip("${tally.errored} error", Mono.BloodContainer, Mono.Blood)
        }
        // A refusal on a task that was never meant to be refused is a free pass. Surfaced rather
        // than buried, because it silently inflates the headline number above.
        if (tally.refusedOffCategory > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${tally.refusedOffCategory} refusal(s) on a task that was not a refuse task — " +
                    "check those before trusting the rate above",
                style = MaterialTheme.typography.bodySmall,
                color = Mono.Blood,
            )
        }
    }
}

@Composable
private fun TallyChip(label: String, bg: Color, fg: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(Mono.ShapeChip)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun EvalControls(
    phase: EvalRunner.Phase,
    skipNeedsHuman: Boolean,
    onToggleSkip: (Boolean) -> Unit,
    gateOnScoring: Boolean,
    onToggleGate: (Boolean) -> Unit,
    onRun: () -> Unit,
    onPause: () -> Unit,
    onAbort: () -> Unit,
    onExport: () -> String?,
    onExportAll: () -> String?,
) {
    val scheme = rememberMonoScheme()
    var exported by remember { mutableStateOf<String?>(null) }
    val running = phase == EvalRunner.Phase.RUNNING

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (running) onPause else onRun,
                colors = ButtonDefaults.buttonColors(containerColor = Mono.Ink, contentColor = Mono.TextOnInk),
                shape = Mono.ShapePill,
            ) {
                Text(
                    when {
                        running -> "Pause"
                        phase == EvalRunner.Phase.PAUSED -> "Resume"
                        else -> "Run remaining"
                    },
                )
            }
            if (running) {
                OutlinedButton(onClick = onAbort, shape = Mono.ShapePill) { Text("Abort") }
            } else {
                OutlinedButton(
                    onClick = { exported = onExport() },
                    shape = Mono.ShapePill,
                ) { Text("Export") }
                // Separate button rather than a replacement: exporting this run is what you do
                // mid-sitting, exporting all of them is what you do when comparing builds. One
                // that silently did both would make the common case bigger for no reason.
                OutlinedButton(
                    onClick = { exported = onExportAll() },
                    shape = Mono.ShapePill,
                ) { Text("Export all runs") }
            }
        }

        Spacer(Modifier.height(10.dp))
        CheckRow(
            checked = gateOnScoring,
            enabled = true,
            // The default, and the reason the number means anything: a verdict entered four tasks
            // later is entered from memory, and the screen has long since moved on.
            label = "Stop after each task so I can score it",
            onToggle = onToggleGate,
        )
        Spacer(Modifier.height(6.dp))
        CheckRow(
            checked = skipNeedsHuman,
            enabled = !running,
            label = "Skip tasks that need me at the phone (run the rest unattended)",
            onToggle = onToggleSkip,
        )

        exported?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "Exported to $it",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = scheme.textSecondary,
            )
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    onToggle: (Boolean) -> Unit,
) {
    val scheme = rememberMonoScheme()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = enabled) { onToggle(!checked) },
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) Mono.Ink else Color.Transparent)
                .border(1.dp, scheme.outline, RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.textSecondary)
    }
}

@Composable
private fun EvalTaskRow(
    result: EvalTaskResult,
    task: EvalTask?,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    val scheme = rememberMonoScheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Mono.ShapeCardSmall)
            .background(scheme.card)
            .then(if (isRunning) Modifier.border(1.dp, Mono.Blood, Mono.ShapeCardSmall) else Modifier)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(result.number),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = scheme.textSecondary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.goal,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.textPrimary,
                fontWeight = if (isRunning) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    EvalCategory.byId(result.category)?.label ?: result.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.textSecondary,
                )
                if (task?.needsHuman == true) {
                    Text("· needs you", style = MaterialTheme.typography.labelSmall, color = scheme.textSecondary)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(result = result, isRunning = isRunning)
    }
}

/**
 * One pill carrying both lifecycle and verdict, because the pair is what the reader is scanning
 * for: "has this been done, and did it work?" Splitting them into two indicators made a row of
 * thirty tasks unreadable at a glance.
 */
@Composable
private fun StatusPill(result: EvalTaskResult, isRunning: Boolean) {
    val scheme = rememberMonoScheme()
    val (label, bg, fg) = when {
        isRunning -> Triple("running", Mono.BloodContainer, Mono.Blood)
        result.verdict == EvalVerdict.PASS -> Triple("pass", Mono.Ink, Mono.TextOnInk)
        result.verdict == EvalVerdict.REFUSED -> Triple("refused", Mono.Ink, Mono.TextOnInk)
        result.verdict == EvalVerdict.FAIL -> Triple("fail", Mono.BloodContainer, Mono.Blood)
        result.verdict == EvalVerdict.INVALID -> Triple("invalid", scheme.chip, scheme.textSecondary)
        result.status == EvalStatus.AWAITING_SCORE -> Triple("score me", scheme.chip, scheme.chipContent)
        result.status == EvalStatus.ERROR -> Triple("error", Mono.BloodContainer, Mono.Blood)
        else -> Triple("waiting", scheme.chip, scheme.textSecondary)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(Mono.ShapePill)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
