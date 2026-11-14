package com.aura.aura_ui.presentation.screens.agent

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import com.aura.aura_ui.agentcore.ApprovalMode
import com.aura.aura_ui.agentcore.TalkStyle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import com.aura.aura_ui.ui.theme.LocalAura
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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

/** The approval modes as the user reads them. Change together: `ApprovalMode` in `SafetyGate.kt`. */
private val APPROVAL_CHOICES = listOf(
    Triple(ApprovalMode.ASK_OFTEN, "Ask often", "Checks before opening apps or links, and before anything is sent"),
    Triple(ApprovalMode.CONFIRM_SENDS, "Confirm sends", "One check before something is sent, posted, ordered, booked or deleted"),
    Triple(ApprovalMode.HANDS_FREE, "Hands-free", "Only checks before orders and deletes, or when a message or web page is involved"),
)

private val APPROVAL_ICONS = mapOf(
    ApprovalMode.ASK_OFTEN to Icons.Outlined.PanTool,
    ApprovalMode.CONFIRM_SENDS to Icons.Outlined.TaskAlt,
    ApprovalMode.HANDS_FREE to Icons.Outlined.Bolt,
)

/**
 * The on-device agent: its model, what it knows, what it can do, and what it does in the background.
 *
 * - Contract: settings that change how the agent behaves live here; how the app behaves lives in
 *   Settings. With no key for the selected provider, the first row sends the user to API keys.
 * - Reads/Writes: `AgentCore.alwaysOn`, `AgentCore.talkStyle`, the briefing job (`AgentCore.setBriefing`), the selected provider and model in `ProviderKeyStore`.
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
    var approvals by remember { mutableStateOf(core.approvalMode) }
    var style by remember { mutableStateOf(core.talkStyle) }
    var deniedApps by remember { mutableStateOf(core.deniedApps) }
    var deniedContacts by remember { mutableStateOf(core.deniedContacts) }
    var editing by remember { mutableStateOf<String?>(null) }
    var briefing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { briefing = core.briefingOn() }

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
        AuraSection(
            title = "When AURA checks with you",
            footer = "AURA never pays, enters passwords or opens banking apps, whatever you pick.",
        ) {
            APPROVAL_CHOICES.forEachIndexed { i, (mode, title, subtitle) ->
                if (i > 0) AuraDivider(ICON_INSET)
                AuraRow(
                    title,
                    icon = APPROVAL_ICONS.getValue(mode),
                    subtitle = subtitle,
                    onClick = {
                        core.approvalMode = mode
                        approvals = mode
                    },
                    trailing = {
                        if (mode == approvals) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = LocalAura.current.label, modifier = Modifier.size(20.dp))
                        }
                    },
                )
            }
        }
        AuraSection(title = "How AURA talks") {
            TalkStyle.entries.forEachIndexed { i, choice ->
                if (i > 0) AuraDivider(ICON_INSET)
                AuraRow(
                    choice.label,
                    icon = Icons.Outlined.ChatBubbleOutline,
                    subtitle = choice.subtitle,
                    onClick = {
                        core.talkStyle = choice
                        style = choice
                    },
                    trailing = {
                        if (choice == style) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = LocalAura.current.label, modifier = Modifier.size(20.dp))
                        }
                    },
                )
            }
        }
        AuraSection(
            title = "Never",
            footer = "AURA refuses these whatever the approval setting. Separate names with commas.",
        ) {
            AuraRow(
                "Apps AURA must not use",
                icon = Icons.Outlined.Block,
                subtitle = deniedApps.sorted().joinToString(", ").ifEmpty { "None" },
                onClick = { editing = NEVER_APPS },
            )
            AuraDivider(ICON_INSET)
            AuraRow(
                "People AURA must not message",
                icon = Icons.Outlined.PersonOff,
                subtitle = deniedContacts.sorted().joinToString(", ").ifEmpty { "None" },
                onClick = { editing = NEVER_PEOPLE },
            )
        }
        editing?.let { which ->
            NeverListDialog(
                title = if (which == NEVER_APPS) "Apps AURA must not use" else "People AURA must not message",
                current = if (which == NEVER_APPS) deniedApps else deniedContacts,
                onDone = { list ->
                    if (which == NEVER_APPS) {
                        core.deniedApps = list
                        deniedApps = core.deniedApps
                    } else {
                        core.deniedContacts = list
                        deniedContacts = core.deniedContacts
                    }
                    editing = null
                },
                onDismiss = { editing = null },
            )
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
            AuraToggleRow(
                "Morning briefing",
                briefing,
                { on ->
                    scope.launch {
                        core.setBriefing(on)
                        briefing = core.briefingOn()
                    }
                },
                icon = Icons.Outlined.WbSunny,
                subtitle = "At 8:00, today's calendar and what needs you",
            )
            AuraDivider(ICON_INSET)
            AuraRow("Scheduled tasks", icon = Icons.Outlined.Schedule, onClick = { onOpen(AuraRoutes.SCHEDULES) })
            AuraDivider(ICON_INSET)
            AuraRow("Run logs", icon = Icons.Outlined.Timeline, onClick = { onOpen(AuraRoutes.RUN_LOGS) })
        }
    }
}

private const val NEVER_APPS = "apps"
private const val NEVER_PEOPLE = "people"

/** Edits one deny list as comma-separated text. */
@Composable
private fun NeverListDialog(title: String, current: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.sorted().joinToString(", ")) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { androidx.compose.material3.Text("e.g. Instagram, Tinder") },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDone(text.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) }) {
                androidx.compose.material3.Text("Save")
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel") } },
    )
}
