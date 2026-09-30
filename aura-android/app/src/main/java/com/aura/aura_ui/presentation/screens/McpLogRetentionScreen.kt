package com.aura.aura_ui.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.mcp.log.LogRetentionPrefs
import com.aura.aura_ui.presentation.components.MonoTopBar
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Mono

/**
 * Phase 10B — pick how many days to keep MCP session logs.
 *
 * Presets cover the common asks (1–3 months) and "Custom" lets the user type
 * any positive integer. The value is read by the daily cleanup worker on its
 * next pass — no service restart needed. Back is owned by the global capsule
 * nav bar, so there is no redundant top-bar back here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun McpLogRetentionScreen(
    onNavigateBack: () -> Unit,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val prefs = remember { LogRetentionPrefs(context) }
    var days by remember { mutableIntStateOf(prefs.retentionDays()) }
    var customMode by remember { mutableStateOf(days !in LogRetentionPrefs.PRESETS) }
    var customText by remember { mutableStateOf(days.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        MonoTopBar(
            title = "Log retention",
            subtitle = "How long session logs are kept",
            scheme = scheme,
        )

        Text(
            "Session logs older than this are deleted automatically once per day.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.textSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(12.dp))

        FlowRow(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogRetentionPrefs.PRESETS.forEach { preset ->
                RetentionChip(
                    label = "$preset days",
                    selected = !customMode && days == preset,
                    scheme = scheme,
                    onClick = {
                        customMode = false
                        days = preset
                        prefs.setRetentionDays(preset)
                    },
                )
            }
            RetentionChip(
                label = "Custom",
                selected = customMode,
                scheme = scheme,
                onClick = {
                    customMode = true
                    customText = days.toString()
                },
            )
        }

        if (customMode) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = Mono.ShapeCardSmall,
                color = scheme.card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { input -> customText = input.filter { it.isDigit() }.take(4) },
                        singleLine = true,
                        label = { Text("Days") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = scheme.chip,
                            unfocusedContainerColor = scheme.chip,
                            focusedIndicatorColor = Mono.Ink,
                            unfocusedIndicatorColor = scheme.outline,
                            focusedLabelColor = scheme.textSecondary,
                            unfocusedLabelColor = scheme.textSecondary,
                            focusedTextColor = scheme.textPrimary,
                            unfocusedTextColor = scheme.textPrimary,
                            cursorColor = Mono.Ink,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        modifier = Modifier.align(Alignment.End),
                        enabled = customText.toIntOrNull()?.let { it >= 1 } == true,
                        onClick = {
                            val value = customText.toIntOrNull()?.coerceAtLeast(1) ?: return@Button
                            days = value
                            prefs.setRetentionDays(value)
                        },
                    ) { Text("Save") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Surface(
            shape = Mono.ShapeCardSmall,
            color = scheme.card,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Currently keeping logs for $days day${if (days == 1) "" else "s"}.",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.textPrimary,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** A Mono selectable chip: filled ink when selected, hairline-outlined chip otherwise. */
@Composable
private fun RetentionChip(
    label: String,
    selected: Boolean,
    scheme: com.aura.aura_ui.ui.theme.MonoScheme,
    onClick: () -> Unit,
) {
    Surface(
        shape = Mono.ShapePill,
        color = if (selected) Mono.Ink else scheme.chip,
        modifier = Modifier
            .clip(Mono.ShapePill)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, scheme.outline, Mono.ShapePill)
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = if (selected) Mono.TextOnInk else scheme.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}
