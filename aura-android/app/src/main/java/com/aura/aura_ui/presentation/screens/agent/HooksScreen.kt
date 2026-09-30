package com.aura.aura_ui.presentation.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.RuleFolder
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.mcpbridge.hooks.HookCatalog
import com.aura.aura_ui.agent.mcpbridge.hooks.HookInfo
import com.aura.aura_ui.presentation.components.ColumnScopeMarker
import com.aura.aura_ui.presentation.components.MonoCardGroup
import com.aura.aura_ui.presentation.components.MonoDivider
import com.aura.aura_ui.presentation.components.MonoRow
import com.aura.aura_ui.presentation.components.MonoSectionLabel
import com.aura.aura_ui.presentation.components.MonoTopBar
import com.aura.aura_ui.presentation.components.rememberMonoScheme

// ============================================================================
// HOOKS — the guard pipeline every tool call passes through, as the ordered
// before → after rail it actually is. Read-only transparency surface ("what
// stands between the model and your device"). Rendered straight from
// [HookCatalog] (the single maintained list, co-located with the chain), so
// this screen never keeps its own copy — add a guard, it appears here.
// ============================================================================

/** Cosmetic icon per hook key; unknown keys get a neutral shield default. */
private fun iconForHook(key: String): ImageVector = when (key) {
    "arg_sanity" -> Icons.Outlined.RuleFolder
    "action_guard" -> Icons.Outlined.FactCheck
    "scope_guard" -> Icons.Outlined.Gavel
    "sensitive_policy" -> Icons.Outlined.Policy
    "foreground_guard" -> Icons.Outlined.PrivacyTip
    "confirm_destructive" -> Icons.Outlined.Shield
    "screen_settle" -> Icons.Outlined.HourglassBottom
    "action_guard_observe" -> Icons.Outlined.Visibility
    "learnings_writer" -> Icons.Outlined.School
    "run_ledger" -> Icons.Outlined.History
    else -> Icons.Outlined.Shield
}

@Composable
fun HooksScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // Back is owned by the global capsule nav bar — no redundant top-bar back.
        MonoTopBar(
            title = "Hooks",
            subtitle = "Every action passes through this pipeline",
            scheme = scheme,
        )

        MonoSectionLabel("Before an action runs", scheme)
        MonoCardGroup(scheme) {
            HookRows(HookCatalog.before)
        }

        MonoSectionLabel("After an action runs", scheme)
        MonoCardGroup(scheme) {
            HookRows(HookCatalog.after)
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/** Render a phase's hooks as divider-separated Mono rows (inside a MonoCardGroup). */
@Composable
private fun ColumnScopeMarker.HookRows(hooks: List<HookInfo>) {
    hooks.forEachIndexed { index, hook ->
        MonoRow(icon = iconForHook(hook.key), title = hook.name, subtitle = hook.description)
        if (index < hooks.size - 1) MonoDivider()
    }
}
