package com.aura.aura_ui.agentcore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraMark
import com.aura.aura_ui.presentation.navigation.AuraRoutes
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura

/** How many recent chats the drawer lists before "View all". */
private const val RECENT_CHATS = 8

/**
 * The drawer: where to go in the app, the latest chats, and the account.
 *
 * - Contract: navigation only. Switches and appearance live on the agent page and in Settings.
 * - Change together: the routes are registered in `AuraAppShell.kt`.
 */
@Composable
fun ChatDrawer(
    vm: ChatViewModel,
    threads: List<ThreadRow>,
    current: String?,
    onNavigate: (String) -> Unit,
    close: () -> Unit,
) {
    val aura = LocalAura.current
    val profile by vm.profile.collectAsState()
    val go: (String) -> Unit = { route ->
        close()
        onNavigate(route)
    }

    Column(Modifier.fillMaxSize().background(aura.surfaceStrong).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AuraMark(size = 26.dp)
            Text("AURA", Modifier.padding(start = 10.dp).weight(1f), style = AuraType.title, color = aura.label)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            item { NavRow("On-device agent", null, mark = true) { go(AuraRoutes.AGENT) } }
            item { NavRow("MCP server", Icons.Outlined.Hub) { go(AuraRoutes.SETTINGS_MCP_CENTER) } }
            item { NavRow("Run logs", Icons.Outlined.Timeline) { go(AuraRoutes.RUN_LOGS) } }
            item { NavRow("Scheduled tasks", Icons.Outlined.Schedule) { go(AuraRoutes.SCHEDULES) } }
            item { NavRow("Settings", Icons.Outlined.Settings) { go(AuraRoutes.SETTINGS) } }
            item {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { AuraDivider() }
            }
            if (threads.isNotEmpty()) {
                item { Text("Chats", Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp), style = AuraType.footnote, color = aura.tertiary) }
            }
            items(threads.take(RECENT_CHATS), key = { it.id }) { thread ->
                ChatRow(thread.title, thread.id == current) {
                    vm.open(thread.id)
                    close()
                }
            }
            if (threads.isNotEmpty()) {
                item {
                    Text(
                        "View all",
                        Modifier.fillMaxWidth().clip(AuraShape.Inner).clickable(role = Role.Button) { go(AuraRoutes.CHATS) }
                            .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                        style = AuraType.callout,
                        color = aura.secondary,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(AuraShape.Pill).background(aura.accent).clickable(onClickLabel = "New chat", role = Role.Button) {
                    vm.open(null)
                    close()
                }.heightIn(min = 48.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = aura.onAccent, modifier = Modifier.size(19.dp))
                Text("New chat", Modifier.padding(start = 8.dp), style = AuraType.headline, color = aura.onAccent)
            }
            Spacer(Modifier.weight(1f))
            Avatar(profile, 44) { go(AuraRoutes.SETTINGS) }
        }
    }
}

@Composable
private fun NavRow(label: String, icon: ImageVector?, mark: Boolean = false, onClick: () -> Unit) {
    val aura = LocalAura.current
    Row(
        Modifier.fillMaxWidth().clip(AuraShape.Inner).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            mark -> AuraMark(size = 20.dp)
            icon != null -> Icon(icon, contentDescription = null, tint = aura.label, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = AuraType.body, color = aura.label)
    }
}

@Composable
private fun ChatRow(title: String, selected: Boolean, onOpen: () -> Unit) {
    val aura = LocalAura.current
    Text(
        title,
        Modifier.fillMaxWidth().clip(AuraShape.Inner).background(if (selected) aura.well else aura.surfaceStrong)
            .clickable(onClickLabel = "Open $title", onClick = onOpen).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 11.dp),
        style = AuraType.callout,
        color = aura.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
