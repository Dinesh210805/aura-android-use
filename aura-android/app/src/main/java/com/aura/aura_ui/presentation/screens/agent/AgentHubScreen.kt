package com.aura.aura_ui.presentation.screens.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.llm.LlmEndpointCatalog
import com.aura.aura_ui.agentcore.AgentCore
import com.aura.aura_ui.mcp.bridge.ProviderKeyStore
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraPage
import com.aura.aura_ui.presentation.components.AuraRow
import com.aura.aura_ui.presentation.components.AuraSection
import com.aura.aura_ui.presentation.components.AuraToggleRow
import com.aura.aura_ui.presentation.navigation.AuraRoutes

private val ICON_INSET = 52.dp

/**
 * The on-device agent: its model, what it knows, what it can do, and what it does in the background.
 *
 * - Contract: settings that change how the agent behaves live here; how the app behaves lives in
 *   Settings. With no key for the selected provider, the first row sends the user to API keys.
 * - Reads/Writes: `AgentCore.alwaysOn`, the selected provider and model in `ProviderKeyStore`.
 */
@Composable
fun AgentHubScreen(onOpen: (route: String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val core = remember { AgentCore.get(context) }
    val keys = remember { ProviderKeyStore(context) }
    val endpoint = remember { keys.getSelectedEndpointId() }
    val needsKey = remember { LlmEndpointCatalog.BUILTINS.firstOrNull { it.id == endpoint }?.requiresKey != false && !keys.isConfigured(endpoint) }
    val model = remember { keys.getSelectedModel(endpoint)?.substringAfterLast('/') }
    var digests by remember { mutableStateOf(core.alwaysOn) }

    AuraPage("On-device agent", modifier) {
        AuraSection {
            if (needsKey) {
                AuraRow("Add an API key", icon = Icons.Outlined.Key, subtitle = "The agent needs one to think", onClick = { onOpen(AuraRoutes.apiKeys(endpoint)) })
                AuraDivider(ICON_INSET)
            }
            AuraRow("Model", icon = Icons.Outlined.Psychology, value = model ?: "Choose", onClick = { onOpen(AuraRoutes.AGENT_BRAIN) })
        }
        AuraSection(title = "Knowledge") {
            AuraRow("Memory", icon = Icons.Outlined.Memory, onClick = { onOpen(AuraRoutes.AGENT_MEMORY) })
            AuraDivider(ICON_INSET)
            AuraRow("Skills", icon = Icons.Outlined.AutoAwesome, onClick = { onOpen(AuraRoutes.AGENT_SKILLS) })
            AuraDivider(ICON_INSET)
            AuraRow("Connectors", icon = Icons.Outlined.Cable, onClick = { onOpen(AuraRoutes.AGENT_CONNECTORS) })
        }
        AuraSection(title = "Capabilities") {
            AuraRow("Tools", icon = Icons.Outlined.Construction, onClick = { onOpen(AuraRoutes.AGENT_TOOLS) })
            AuraDivider(ICON_INSET)
            AuraRow("Hooks", icon = Icons.Outlined.Security, onClick = { onOpen(AuraRoutes.AGENT_HOOKS) })
        }
        AuraSection(title = "In the background") {
            AuraToggleRow(
                "Notification digests",
                digests,
                {
                    core.alwaysOn = it
                    digests = it
                },
                icon = Icons.Outlined.NotificationsActive,
                subtitle = "A summary of what came in while you were away",
            )
            AuraDivider(ICON_INSET)
            AuraRow("Scheduled tasks", icon = Icons.Outlined.Schedule, onClick = { onOpen(AuraRoutes.SCHEDULES) })
            AuraDivider(ICON_INSET)
            AuraRow("Run logs", icon = Icons.Outlined.Timeline, onClick = { onOpen(AuraRoutes.RUN_LOGS) })
        }
    }
}
