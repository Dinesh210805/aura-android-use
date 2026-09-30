package com.aura.aura_ui.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.agent.skills.BundledSkillSource
import com.aura.aura_ui.agent.skills.Skill
import com.aura.aura_ui.agent.skills.UserSkillStore
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import com.aura.aura_ui.presentation.components.CharcoalCard
import com.aura.aura_ui.presentation.components.MonoCardGroup
import com.aura.aura_ui.presentation.components.MonoDivider
import com.aura.aura_ui.presentation.components.MonoRow
import com.aura.aura_ui.presentation.components.MonoSectionLabel
import com.aura.aura_ui.presentation.components.MonoSwitch
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Capsule

// ============================================================================
// SKILLS — full management (Capsule design, 2026-07-10):
//   · Installed (bundled) skills listed read-only — tap to read the playbook
//   · Your skills — toggle on/off, tap to open & edit, delete from the editor
//   · "Teach a new skill" charcoal CTA → the same editor, blank
// Bundled skills are always-on for the agent (trusted tier); only USER skills
// are editable, per the skills sub-project's trust rules.
// ============================================================================

/** What the bottom sheet is currently doing. */
private sealed interface SkillSheet {
    data object New : SkillSheet
    data class Edit(val skill: Skill) : SkillSheet
    data class View(val skill: Skill) : SkillSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm = remember {
        SkillsViewModel(UserSkillStore(context), BundledSkillSource.fromAssets(context))
    }
    val state by vm.state.collectAsState()
    val scheme = rememberMonoScheme()
    val cs = Capsule.scheme()

    var sheet by remember { mutableStateOf<SkillSheet?>(null) }

    AuraScreenScaffold(
        title = "Skills",
        subtitle = "Reusable task playbooks",
        onBack = onNavigateBack,
    ) {
        Spacer(Modifier.height(4.dp))

        // ── Teach a new skill — the screen's charcoal object.
        CharcoalCard(cs = cs, onClick = { vm.clearError(); sheet = SkillSheet.New }) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Teach a new skill",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = cs.onChar,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Write the steps once — AURA follows them on demand.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onCharDim,
                    )
                }
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(cs.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add skill", tint = cs.onAccent, modifier = Modifier.size(20.dp))
                }
            }
        }

        // ── Your skills — editable, toggleable.
        MonoSectionLabel("Your skills · ${state.skills.size}", scheme)
        if (state.skills.isEmpty()) {
            Text(
                "Nothing yet. Teach AURA its first skill above.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.textSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            MonoCardGroup(scheme) {
                state.skills.forEachIndexed { index, skill ->
                    MonoRow(
                        icon = Icons.Outlined.EditNote,
                        title = skill.name,
                        subtitle = skill.description,
                        onClick = { vm.clearError(); sheet = SkillSheet.Edit(skill) },
                        trailing = {
                            MonoSwitch(
                                checked = skill.id in state.enabledIds,
                                onCheckedChange = { vm.toggle(skill.id, it) },
                                scheme = scheme,
                            )
                        },
                    )
                    if (index < state.skills.lastIndex) MonoDivider()
                }
            }
        }

        // ── Installed (bundled) — read-only, always on.
        MonoSectionLabel("Installed · ${state.bundled.size}", scheme)
        if (state.bundled.isEmpty()) {
            Text(
                "No built-in skills shipped in this build.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.textSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            MonoCardGroup(scheme) {
                state.bundled.forEachIndexed { index, skill ->
                    MonoRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = skill.name,
                        subtitle = skill.description,
                        onClick = { sheet = SkillSheet.View(skill) },
                        trailing = {
                            Text(
                                "BUILT-IN",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                color = scheme.textSecondary,
                            )
                        },
                    )
                    if (index < state.bundled.lastIndex) MonoDivider()
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    // ── The sheet: editor (new/edit) or read-only playbook (view).
    sheet?.let { current ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = sheetState,
            containerColor = cs.card,
        ) {
            when (current) {
                is SkillSheet.View -> SkillPlaybook(skill = current.skill, cs = cs)
                is SkillSheet.New -> SkillEditor(
                    cs = cs,
                    error = state.addError,
                    onSave = { n, d, w, b -> if (vm.addSkill(n, d, w, b)) sheet = null },
                )
                is SkillSheet.Edit -> SkillEditor(
                    cs = cs,
                    initial = current.skill,
                    error = state.addError,
                    onSave = { n, d, w, b -> if (vm.updateSkill(current.skill.id, n, d, w, b)) sheet = null },
                    onDelete = { vm.delete(current.skill.id); sheet = null },
                )
            }
        }
    }
}

/** Read-only view of a bundled skill's playbook. */
@Composable
private fun SkillPlaybook(skill: Skill, cs: com.aura.aura_ui.ui.theme.CapsuleScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            "BUILT-IN SKILL",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = cs.accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            skill.name,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
            color = cs.ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = cs.sub)
        if (skill.whenToUse.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "WHEN AURA USES IT",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                color = cs.sub,
            )
            Spacer(Modifier.height(4.dp))
            Text(skill.whenToUse, style = MaterialTheme.typography.bodyMedium, color = cs.ink)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "THE PLAYBOOK",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color = cs.sub,
        )
        Spacer(Modifier.height(6.dp))
        Surface(shape = Capsule.ShapeCard, color = cs.cardSoft, modifier = Modifier.fillMaxWidth()) {
            Text(
                skill.body.ifBlank { "(no steps)" },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.ink,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** Create/edit form for a USER skill. [initial] null = new skill. */
@Composable
private fun SkillEditor(
    cs: com.aura.aura_ui.ui.theme.CapsuleScheme,
    error: String?,
    onSave: (name: String, description: String, whenToUse: String, body: String) -> Unit,
    initial: Skill? = null,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var whenToUse by remember { mutableStateOf(initial?.whenToUse.orEmpty()) }
    var body by remember { mutableStateOf(initial?.body.orEmpty()) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = cs.ink,
        unfocusedBorderColor = cs.line,
        cursorColor = cs.accent,
        focusedLabelColor = cs.ink,
        unfocusedLabelColor = cs.sub,
        focusedTextColor = cs.ink,
        unfocusedTextColor = cs.ink,
        focusedContainerColor = cs.card,
        unfocusedContainerColor = cs.card,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (initial == null) "Teach a skill" else "Edit skill",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
            color = cs.ink,
        )
        // SK2 — user skills are trusted, every-run prompt content; pasted internet
        // text gets that authority too. Say so where it's typed.
        Text(
            "Skills steer AURA on every run. Only save steps you wrote or trust — " +
                "don't paste instructions from sources you don't know.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.sub,
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name") }, singleLine = true,
            colors = fieldColors, shape = Capsule.ShapeChipWell,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it },
            label = { Text("What it does") },
            colors = fieldColors, shape = Capsule.ShapeChipWell,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = whenToUse, onValueChange = { whenToUse = it },
            label = { Text("When to use it (optional)") },
            colors = fieldColors, shape = Capsule.ShapeChipWell,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body, onValueChange = { body = it },
            label = { Text("The steps — one per line") },
            minLines = 4,
            colors = fieldColors, shape = Capsule.ShapeChipWell,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(it, color = cs.accent, style = MaterialTheme.typography.bodySmall)
        }

        // Save — the ink pill.
        Surface(
            onClick = { onSave(name, description, whenToUse, body) },
            shape = Capsule.ShapePill,
            color = if (name.isNotBlank() && description.isNotBlank()) cs.capsule else cs.line,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (initial == null) "Add skill" else "Save changes",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onCapsule,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }

        if (onDelete != null) {
            Surface(
                onClick = onDelete,
                shape = Capsule.ShapePill,
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Delete this skill",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.accent,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
