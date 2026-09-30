package com.aura.aura_ui.presentation.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import com.aura.aura_ui.presentation.components.CharcoalCard
import com.aura.aura_ui.presentation.components.MonoCardGroup
import com.aura.aura_ui.presentation.components.MonoDivider
import com.aura.aura_ui.presentation.components.MonoRow
import com.aura.aura_ui.presentation.components.MonoSectionLabel
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Capsule

// ============================================================================
// AGENT HUB — Capsule design: the Brain as a charcoal hero card (the screen's
// one dark object), then white grouped rows for capabilities & knowledge.
// Rule (spec §3): changes how the AGENT behaves → here; how the APP behaves
// → Settings.
// ============================================================================

@Composable
fun AgentHubScreen(
    onOpenBrain: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenHooks: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenMcpServers: () -> Unit,
    onOpenVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()
    val cs = Capsule.scheme()

    AuraScreenScaffold(
        title = "Agent",
        subtitle = "How AURA thinks and acts",
        modifier = modifier,
    ) {
        Spacer(Modifier.height(6.dp))

        // ── The Brain — the screen's charcoal focal object.
        CharcoalCard(cs = cs, onClick = onOpenBrain) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(cs.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = cs.onAccent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.size(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "THE BRAIN",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                            color = cs.onCharDim,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Model & provider",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = cs.onChar,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = cs.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Which AI thinks for AURA — provider, API key and model, on your own key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onCharDim,
                )
            }
        }

        MonoSectionLabel("Capabilities", scheme)
        MonoCardGroup(scheme) {
            MonoRow(
                icon = Icons.Outlined.Construction,
                title = "Tools",
                subtitle = "Everything AURA can do on this device",
                onClick = onOpenTools,
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.Security,
                title = "Hooks",
                subtitle = "The guard pipeline actions pass through",
                onClick = onOpenHooks,
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "Skills",
                subtitle = "Reusable task playbooks",
                onClick = onOpenSkills,
            )
        }

        MonoSectionLabel("Knowledge", scheme)
        MonoCardGroup(scheme) {
            MonoRow(
                icon = Icons.Outlined.Memory,
                title = "Memory",
                subtitle = "Memories, commitments & learnings",
                onClick = onOpenMemory,
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.Extension,
                title = "MCP Servers",
                subtitle = "External tool servers",
                onClick = onOpenMcpServers,
            )
        }

        MonoSectionLabel("Conversation", scheme)
        MonoCardGroup(scheme) {
            MonoRow(
                icon = Icons.Outlined.RecordVoiceOver,
                title = "Voice & Companion",
                subtitle = "Gemini Live, voices, conversation",
                onClick = onOpenVoice,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
