package com.aura.aura_ui.presentation.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.mcpbridge.client.McpConnectionState
import com.aura.aura_ui.agent.mcpbridge.client.McpSecretStore
import com.aura.aura_ui.agent.mcpbridge.client.McpServerStore
import com.aura.aura_ui.agent.mcpbridge.client.McpTransportType

/**
 * Settings → MCP servers. Lists configured third-party MCP servers (enable/disable, delete) and
 * provides an add form whose URL is validated through [com.aura.aura_ui.agent.mcpbridge.client.McpUrlValidator]
 * (https-only) before persisting. Secrets are never shown here — only the config list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm = remember { McpServersViewModel(McpServerStore(context), McpSecretStore(context)) }
    val state by vm.state.collectAsState()

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransportType.STREAMABLE_HTTP) }

    AuraScreenScaffold(
        title = "MCP Servers",
        subtitle = "External tool servers",
        onBack = onNavigateBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.servers.isEmpty()) {
                Text(
                    "No MCP servers yet. Add one below to give the agent extra tools.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                    Column {
                        state.servers.forEachIndexed { index, row ->
                            ServerRowItem(
                                name = row.config.displayName,
                                url = row.config.url,
                                transport = row.config.transport,
                                enabled = row.config.enabled,
                                state = row.state,
                                onToggle = { vm.toggle(row.config.id, it) },
                                onDelete = { vm.delete(row.config.id) },
                            )
                            if (index < state.servers.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }

            Text("ADD SERVER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server URL (https://…)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        isError = state.addError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        McpTransportType.entries.forEach { t ->
                            FilterChip(
                                selected = transport == t,
                                onClick = { transport = t },
                                label = { Text(t.name.lowercase().replace('_', ' ')) },
                            )
                        }
                    }
                    state.addError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            if (vm.addServer(name, url, transport)) {
                                name = ""; url = ""
                            }
                        },
                        enabled = name.isNotBlank() && url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add server") }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ServerRowItem(
    name: String,
    url: String,
    transport: McpTransportType,
    enabled: Boolean,
    state: McpConnectionState,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${hostOf(url)} · ${transport.name.lowercase().replace('_', ' ')} · ${stateLabel(state)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

private fun stateLabel(state: McpConnectionState): String = when (state) {
    McpConnectionState.Disabled -> "disabled"
    McpConnectionState.Connecting -> "connecting…"
    is McpConnectionState.Connected -> "connected (${state.toolCount} tools)"
    is McpConnectionState.NeedsAuth -> "needs sign-in"
    is McpConnectionState.Failed -> "failed"
}
