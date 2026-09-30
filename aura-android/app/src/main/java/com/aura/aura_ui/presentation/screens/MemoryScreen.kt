package com.aura.aura_ui.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.memory.Commitment
import com.aura.aura_ui.agent.memory.MemoryEntry
import com.aura.aura_ui.agent.memory.UserProfile
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Memory. A read-only inspector over the agent's encrypted, on-device memory: learned
 * navigation paths (per app · goal) and conversation memory entries. Memory is agent-authored (no
 * add — V.7), so the only controls are the learnings on/off toggle and a Clear-all privacy control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm = remember { MemoryViewModel(context) }
    val state by vm.state.collectAsState()

    AuraScreenScaffold(
        title = "Memory",
        subtitle = "Memories, commitments & learnings",
        onBack = onNavigateBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Everything here is stored encrypted on this device. Learned paths are hints the agent " +
                    "re-verifies against the live screen — never sensitive content.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!state.memoryEncrypted) {
                // The fail-closed store is silently dropping writes on this device. Without this
                // line the only symptom the user ever sees is AURA forgetting everything.
                Text(
                    "This device's secure keystore is unavailable, so nothing new can be saved to " +
                        "memory. Existing entries still show.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            ProfileCard(profile = state.profile, vm = vm)

            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Learn from successful tasks", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Record verified navigation paths to speed up future runs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.learningsEnabled, onCheckedChange = { vm.setLearningsEnabled(it) })
                }
            }

            // M3/M13 — commitments used to be invisible here: the user couldn't see why
            // the companion kept reminding, nor stop it. Pending ones get a Done control.
            if (state.commitments.isNotEmpty()) {
                Text("REMINDERS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                    Column {
                        state.commitments.forEachIndexed { index, c ->
                            CommitmentRowItem(c, onDone = { vm.dismissCommitment(c.id) })
                            if (index < state.commitments.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }

            if (state.appLessons.isNotEmpty()) {
                Text("LEARNED · BY APP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                state.appLessons.forEach { app ->
                    AppLessonsCard(app, vm)
                }
            }

            // Assistant memory, grouped exactly as the model receives it. The old single
            // "REMEMBERED" list showed facts, past-chat summaries and task outcomes as one
            // undifferentiated pile — which is also how the model used to be handed them.
            MemorySection("ABOUT YOU", state.assistant.facts, vm)
            MemorySection("HOW YOU WANT AURA TO BEHAVE", state.assistant.preferences, vm)
            MemorySection("HOW YOU SPEAK", state.assistant.style, vm)
            MemorySection("PAST CONVERSATIONS", state.assistant.episodes, vm)
            MemorySection("TASK LOG · ONLY WHEN ASKED", state.assistant.other, vm)

            if (state.appLessons.isEmpty() && state.assistant.isEmpty) {
                Text(
                    "Nothing learned yet. Run a task to completion and the path that worked will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { vm.clearAll() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear all memory") }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One app's collapsed lessons card (spec 2026-07-17): app name + counts, expanding
 * to Paths / Recoveries / Quirks / Dead-ends with per-entry delete + Forget-app.
 */
@Composable
private fun AppLessonsCard(app: AppLessonsUi, vm: MemoryViewModel) {
    var expanded by rememberSaveable(app.appPackage) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.appLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${app.paths.size} paths · ${app.recoveries.size} recoveries · ${app.quirks.size} quirks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (expanded) "▾" else "▸",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                app.paths.forEach { entry ->
                    LessonRow(
                        title = entry.steps.joinToString(" → "),
                        subtitle = "${entry.goalType} · succeeded ${entry.successCount}× · ${entry.source}",
                        onDelete = { vm.deletePath(entry.appPackage, entry.goalType, entry.steps) },
                    )
                }
                app.recoveries.forEach { f ->
                    LessonRow(
                        title = f.text,
                        subtitle = "recovery · seen ${f.count}× · ${f.source}",
                        onDelete = { vm.deleteFact(f.appPackage, f.kind, f.text) },
                    )
                }
                app.quirks.forEach { f ->
                    LessonRow(
                        title = f.text,
                        subtitle = "quirk · confirmed ${f.count}× · ${f.source}",
                        onDelete = { vm.deleteFact(f.appPackage, f.kind, f.text) },
                    )
                }
                app.antiPatterns.forEach { f ->
                    LessonRow(
                        title = f.text,
                        subtitle = "dead end · ${f.count} strikes · ${f.source}",
                        onDelete = { vm.deleteFact(f.appPackage, f.kind, f.text) },
                    )
                }
                TextButton(
                    onClick = { vm.forgetApp(app.appPackage) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Forget this app") }
            }
        }
    }
}

@Composable
private fun LessonRow(title: String, subtitle: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
private fun CommitmentRowItem(c: Commitment, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (c.done) "delivered" else "due ${formatDue(c.dueAtEpochMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (c.done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (!c.done) {
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

private fun formatDue(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

/**
 * The one hand-authored card on this screen. Name and languages are not memories — they are a
 * profile the user owns, so no model can edit or evict them (see `UserProfile`). Both feed the
 * prompt directly: the name is how AURA addresses them, the language list is a hard boundary on
 * what it may speak.
 *
 * Edits commit on Save rather than per keystroke: every commit re-reads the encrypted store to
 * rebuild the screen, and half-typed text is not a language.
 */
@Composable
private fun ProfileCard(profile: UserProfile, vm: MemoryViewModel) {
    // Re-seeded whenever the stored profile changes, so an external edit is not overwritten by
    // stale local text.
    var name by remember(profile) { mutableStateOf(profile.name.orEmpty()) }
    var languages by remember(profile) { mutableStateOf(profile.languages.joinToString(", ")) }
    val dirty = name != profile.name.orEmpty() || languages != profile.languages.joinToString(", ")

    Text("YOU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("What AURA calls you") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = languages,
                onValueChange = { languages = it },
                label = { Text("Languages you know") },
                supportingText = {
                    Text("Comma-separated, best first. AURA speaks only these, and opens in the first one.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (dirty) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        name = profile.name.orEmpty()
                        languages = profile.languages.joinToString(", ")
                    }) { Text("Cancel") }
                    TextButton(onClick = {
                        vm.setName(name)
                        vm.setLanguages(languages)
                    }) { Text("Save") }
                }
            }
        }
    }
}

/** One titled group of assistant memories; renders nothing when the group is empty. */
@Composable
private fun MemorySection(title: String, entries: List<MemoryEntry>, vm: MemoryViewModel) {
    if (entries.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
        Column {
            entries.forEachIndexed { index, entry ->
                MemoryRowItem(
                    entry = entry,
                    onTogglePin = { vm.setMemoryPinned(entry.id, !entry.pinned) },
                    onDelete = { vm.deleteMemory(entry.id) },
                )
                if (index < entries.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun MemoryRowItem(entry: MemoryEntry, onTogglePin: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
            Row {
                if (entry.confirmations > 1) {
                    Text(
                        "said ${entry.confirmations}×",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (entry.pinned) {
                    Text(
                        "pinned",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (entry.sensitive) {
                    Text(
                        "sensitive · only when you ask",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        TextButton(onClick = onTogglePin) { Text(if (entry.pinned) "Unpin" else "Pin") }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}
