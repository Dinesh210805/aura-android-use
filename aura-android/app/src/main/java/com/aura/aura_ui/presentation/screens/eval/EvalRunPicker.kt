package com.aura.aura_ui.presentation.screens.eval

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.eval.EvalSuiteRun
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Mono
import java.text.DateFormat
import java.util.Date

/**
 * Landing state of the cockpit: start a sitting, or reopen one you did not finish.
 *
 * ### Why reopening is first-class rather than an edge case
 *
 * On a free tier a suite sitting is routinely interrupted — quota runs out, the phone is needed for
 * something else, a task hangs. If the only affordance were "start a run", every interruption would
 * mean starting over, which on 500 requests a day means never finishing. A half-done run is the
 * normal case, so it is a row you tap, not a recovery path.
 *
 * The label matters more than it looks: a run is only comparable to another run of the same build
 * on the same model, so "baseline" vs "after prompt rewrite" is the whole point of keeping them
 * side by side.
 */
@Composable
fun EvalRunPicker(
    runs: List<EvalSuiteRun>,
    auraCount: Int,
    androidWorldCount: Int,
    coverageGaps: List<String>,
    problems: List<String>,
    onCreate: (label: String, androidWorld: Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val scheme = rememberMonoScheme()
    var label by remember { mutableStateOf("") }
    // Which benchmark this sitting is. Never both: the two are graded by different referees — a
    // human watching the phone vs. AndroidWorld reading device state — so a single success rate
    // spanning them would be two numbers averaged into a third that means nothing.
    var androidWorld by remember { mutableStateOf(false) }
    val taskCount = if (androidWorld) androidWorldCount else auraCount
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize()) {
        // A structural problem in the suite corrupts a measurement rather than merely looking
        // untidy — a duplicated goal string makes two tasks indistinguishable in every trace — so
        // it is shown before the button that would start spending quota on it.
        if (problems.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(Mono.ShapeCardSmall)
                    .background(scheme.bloodContainer)
                    .padding(14.dp),
            ) {
                Text(
                    "Suite has ${problems.size} problem(s)",
                    style = MaterialTheme.typography.titleSmall,
                    color = Mono.Blood,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                problems.take(6).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall, color = Mono.Blood)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(Mono.ShapeCard)
                .background(scheme.card)
                .padding(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuiteChip(
                    label = "AURA suite · $auraCount",
                    selected = !androidWorld,
                    onClick = { androidWorld = false },
                )
                SuiteChip(
                    label = "AndroidWorld · $androidWorldCount",
                    selected = androidWorld,
                    onClick = { androidWorld = true },
                )
            }
            Spacer(Modifier.height(12.dp))

            Text(
                "$taskCount tasks loaded",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.textPrimary,
            )
            if (androidWorld) {
                // Said before the button, not after the first task fails. AndroidWorld's setup and
                // scoring are Python driving adb; the phone cannot do either, so a sitting started
                // without the host up would stop at task 1 with nothing measured.
                Text(
                    "Google's benchmark. Setup and scoring run on the PC — start " +
                        "scripts/bench/referee.py first, or every task will stop before it runs. " +
                        "Verdicts and screen recordings come back automatically; no scoring by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                )
            } else if (coverageGaps.isEmpty()) {
                Text(
                    "Every category meets its floor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                )
            } else {
                // Not an error. A thin suite is a legitimate cheap first baseline — but a number
                // from an under-covered category reads exactly like a real one, so say which.
                Text(
                    "Under-covered: ${coverageGaps.joinToString(", ")}. Numbers from those " +
                        "categories are a smoke signal, not a measurement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                )
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name this run (e.g. baseline)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onCreate(label.ifBlank { if (androidWorld) "androidworld" else "run" }, androidWorld) },
                enabled = taskCount > 0,
                shape = Mono.ShapePill,
                colors = ButtonDefaults.buttonColors(containerColor = Mono.Ink, contentColor = Mono.TextOnInk),
            ) { Text("Start a new run") }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Previous runs",
            style = MaterialTheme.typography.titleSmall,
            color = scheme.textPrimary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        if (runs.isEmpty()) {
            Text(
                "None yet.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(runs, key = { it.id }) { run ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Mono.ShapeCardSmall)
                        .background(scheme.card)
                        .clickable { onOpen(run.id) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            run.label.ifBlank { run.id },
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.textPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            // Build is not decoration: a score compared across two builds is not a
                            // comparison, and this is the only place that fact is recorded.
                            "${df.format(Date(run.startedAtMillis))} · build ${run.buildVersionCode} · " +
                                "${run.taskCount} tasks",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = scheme.textSecondary,
                        )
                    }
                    TextButton(onClick = { onDelete(run.id) }) {
                        Text("Delete", color = Mono.Blood, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Which of the two benchmarks this sitting is.
 *
 * A chip pair rather than a dropdown because the choice is binary, permanent for the life of the
 * run, and the counts are the information — "30" and "116" say more about what you are committing
 * to than either name does.
 */
@Composable
private fun SuiteChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = rememberMonoScheme()
    Box(
        Modifier
            .clip(Mono.ShapeChip)
            .background(if (selected) Mono.Ink else scheme.card)
            .border(
                BorderStroke(1.dp, if (selected) Mono.Ink else scheme.outline),
                Mono.ShapeChip,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Mono.TextOnInk else scheme.textSecondary,
        )
    }
}
