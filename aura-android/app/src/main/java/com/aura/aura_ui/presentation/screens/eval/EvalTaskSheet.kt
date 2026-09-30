package com.aura.aura_ui.presentation.screens.eval

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.eval.EvalController
import com.aura.aura_ui.eval.EvalStatus
import com.aura.aura_ui.eval.EvalTask
import com.aura.aura_ui.eval.EvalTaskResult
import com.aura.aura_ui.eval.EvalVerdict
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Mono

/**
 * One task, up close: what to look for, what the agent said, its raw trace, and the four buttons
 * that decide the only number this project gates on.
 *
 * ### Why the truth check sits directly above the buttons
 *
 * Scoring used to happen on a laptop, minutes later, from memory. The screen is single mutable
 * ground truth — "was audio actually playing?" cannot be recovered from a trace afterwards — so the
 * verdict has to be entered while the evidence is still on the phone, with the criterion visible at
 * the moment of judging rather than in a file the scorer half-remembers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvalTaskSheet(
    result: EvalTaskResult,
    task: EvalTask?,
    canRun: Boolean,
    onDismiss: () -> Unit,
    onScore: (EvalVerdict, String) -> Unit,
    onSaveNotes: (String) -> Unit,
    onClearScore: () -> Unit,
    onRerun: () -> Unit,
    onOpenTrace: (String) -> Unit,
) {
    val scheme = rememberMonoScheme()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var notes by remember(result.number) { mutableStateOf(result.notes) }
    var trace by remember(result.sessionId) { mutableStateOf<String?>(null) }
    var showTrace by remember(result.number) { mutableStateOf(false) }
    var savedFlash by remember(result.number) { mutableStateOf(false) }

    LaunchedEffect(result.sessionId, showTrace) {
        if (showTrace && trace == null) {
            result.sessionId?.let { trace = EvalController.traceJson(it) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.canvas,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Task ${result.number} · ${result.category}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.textSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                result.goal,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.textPrimary,
            )

            Spacer(Modifier.height(16.dp))
            SheetCard(title = "What counts as success") {
                Text(
                    result.truthCheck.ifBlank { task?.truthCheck.orEmpty() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.textPrimary,
                )
            }

            if (result.outcome.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SheetCard(title = if (result.status == EvalStatus.ERROR) "What went wrong" else "What AURA said") {
                    Text(
                        result.outcome,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.status == EvalStatus.ERROR) Mono.Blood else scheme.textSecondary,
                    )
                    // The agent's closing sentence is its claim about itself, and that claim has
                    // been wrong. Saying so here is cheaper than hoping the scorer remembers.
                    if (result.status == EvalStatus.AWAITING_SCORE) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This is what AURA claims. Check the phone, not this text.",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Your verdict", style = MaterialTheme.typography.titleSmall, color = scheme.textPrimary)
            Spacer(Modifier.height(8.dp))

            VerdictButtons(
                selected = result.verdict,
                isRefuseTask = result.category == com.aura.aura_ui.eval.EvalCategory.REFUSE.id,
                onPick = { onScore(it, notes); savedFlash = true },
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (what you saw)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            // Notes used to be written only as a side effect of tapping a verdict, so anything
            // typed AFTER scoring — which is the normal order, since you decide pass/fail first
            // and then explain it — was silently dropped when the sheet closed. An explicit save
            // that reports what it did is the fix: a note the user believes was kept and was not
            // is worse than no note at all.
            val notesDirty = notes != result.notes
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        onSaveNotes(notes)
                        savedFlash = true
                    },
                    enabled = notesDirty,
                    shape = Mono.ShapePill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Mono.Ink,
                        contentColor = Mono.TextOnInk,
                    ),
                ) { Text("Save notes") }
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        notesDirty -> "unsaved"
                        savedFlash -> "saved"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (notesDirty) Mono.Blood else scheme.textSecondary,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.verdict != null) {
                    OutlinedButton(onClick = onClearScore, shape = Mono.ShapePill) { Text("Unscore") }
                }
                OutlinedButton(
                    onClick = onRerun,
                    enabled = canRun,
                    shape = Mono.ShapePill,
                ) { Text("Run this again") }
            }
            if (result.attempts > 1) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${result.attempts} attempts in this run",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.textSecondary,
                )
            }

            Spacer(Modifier.height(20.dp))
            if (result.sessionId == null) {
                Text(
                    "No trace — this task never produced a session log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showTrace = !showTrace },
                        shape = Mono.ShapePill,
                    ) { Text(if (showTrace) "Hide JSON" else "Show JSON") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onOpenTrace(result.sessionId) },
                        shape = Mono.ShapePill,
                    ) { Text("Open full trace") }
                }
                if (showTrace) {
                    Spacer(Modifier.height(10.dp))
                    TraceBlock(trace)
                }
            }
        }
    }
}

/**
 * Four verdicts, and the two that look similar are labelled by their consequence rather than their
 * name — `refused` counts as a pass and `invalid` leaves the score entirely, which is the single
 * easiest thing for a tired scorer to get backwards.
 */
@Composable
private fun VerdictButtons(
    selected: EvalVerdict?,
    isRefuseTask: Boolean,
    onPick: (EvalVerdict) -> Unit,
) {
    val scheme = rememberMonoScheme()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VerdictButton("Pass", "the world changed", EvalVerdict.PASS, selected, onPick, Modifier.weight(1f))
            VerdictButton("Fail", "it did not", EvalVerdict.FAIL, selected, onPick, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VerdictButton(
                "Refused",
                if (isRefuseTask) "correct — counts as a pass" else "declined · counts as a PASS",
                EvalVerdict.REFUSED, selected, onPick, Modifier.weight(1f),
            )
            VerdictButton(
                "Invalid",
                "could not be attempted · excluded",
                EvalVerdict.INVALID, selected, onPick, Modifier.weight(1f),
            )
        }
        if (!isRefuseTask) {
            Text(
                "Refused counts as a pass, so only use it when declining was genuinely the right " +
                    "answer. A missing contact or a logged-out app is Invalid.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.textSecondary,
            )
        }
    }
}

@Composable
private fun VerdictButton(
    label: String,
    hint: String,
    verdict: EvalVerdict,
    selected: EvalVerdict?,
    onPick: (EvalVerdict) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()
    val isSelected = selected == verdict
    Button(
        onClick = { onPick(verdict) },
        modifier = modifier,
        shape = Mono.ShapeCardSmall,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Mono.Ink else scheme.card,
            contentColor = if (isSelected) Mono.TextOnInk else scheme.textPrimary,
        ),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = if (isSelected) Mono.TextOnInkDim else scheme.textSecondary,
            )
        }
    }
}

/**
 * The raw trace, horizontally scrollable and selectable.
 *
 * Not pretty-printed further and not truncated: a previous version of the trace UI silently capped
 * payloads at 2 KB and cut `perceive` output mid-JSON on every step, which made traces look
 * malformed when the capture was fine. Showing the file as it is on disk removes that whole class
 * of confusion.
 */
@Composable
private fun TraceBlock(trace: String?) {
    val scheme = rememberMonoScheme()
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(Mono.ShapeCardSmall)
            .background(Mono.Ink)
            .border(1.dp, scheme.outline, Mono.ShapeCardSmall)
            .padding(12.dp),
    ) {
        if (trace == null) {
            Text("Loading trace…", color = Mono.TextOnInkDim, style = MaterialTheme.typography.bodySmall)
        } else {
            SelectionContainer {
                Text(
                    trace,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                    color = Mono.TextOnInk,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SheetCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val scheme = rememberMonoScheme()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Mono.ShapeCardSmall)
            .background(scheme.card)
            .padding(14.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.textSecondary,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        content()
    }
}
