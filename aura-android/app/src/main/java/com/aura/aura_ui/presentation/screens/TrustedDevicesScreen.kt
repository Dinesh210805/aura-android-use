package com.aura.aura_ui.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.mcp.AppTrustedClients
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import com.aura.aura_ui.ui.theme.Capsule
import com.aura.mcp.bridge.TrustedClient
import java.text.DateFormat
import java.util.Date

/**
 * Trusted Devices — the computers the user has approved to drive this phone
 * over MCP/WebRTC. Each row shows self-declared name/host/OS plus first- and
 * last-connected times; the user can revoke one (its next connect re-prompts)
 * or revoke all. Trust keys off the token; the metadata here is display-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedDevicesScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val cs = Capsule.scheme()
    val store = remember { AppTrustedClients.get(context) }
    val clients by store.clients.collectAsState()
    var confirmRevokeAll by remember { mutableStateOf(false) }

    AuraScreenScaffold(title = "Trusted Devices", subtitle = "Computers allowed to drive this phone") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "These computers can reconnect and control this device without a new approval. " +
                    "Revoke any you don't recognise — it will have to be approved again next time.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.sub,
            )

            if (clients.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Computer,
                            null,
                            tint = cs.sub,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "No trusted computers yet. Pair one from your PC and approve it — " +
                                "it will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.sub,
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Column {
                        clients.sortedByDescending { it.lastConnectedAt }.forEachIndexed { i, client ->
                            TrustedDeviceRow(
                                client = client,
                                onRevoke = { store.revoke(client.token) },
                                cs = cs,
                            )
                            if (i < clients.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { confirmRevokeAll = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.accent),
                ) {
                    Icon(Icons.Filled.LinkOff, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Revoke all")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmRevokeAll) {
        AlertDialog(
            onDismissRequest = { confirmRevokeAll = false },
            title = { Text("Revoke all trusted computers?") },
            text = {
                Text(
                    "Every computer will need to be approved again the next time it connects. " +
                        "Any live connection is unaffected until it reconnects.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevokeAll = false
                    store.revokeAll()
                }) { Text("Revoke all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevokeAll = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TrustedDeviceRow(
    client: TrustedClient,
    onRevoke: () -> Unit,
    cs: com.aura.aura_ui.ui.theme.CapsuleScheme,
) {
    var confirm by remember(client.token) { mutableStateOf(false) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(cs.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Computer, null, tint = cs.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                client.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val origin = listOfNotNull(
                client.host?.takeIf { it.isNotBlank() },
                client.platform?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (origin.isNotBlank()) {
                Text(origin, style = MaterialTheme.typography.bodySmall, color = cs.sub)
            }
            Text(
                buildString {
                    append("id ")
                    append(client.tokenId)
                    if (client.lastConnectedAt > 0) {
                        append("  ·  last ")
                        append(df.format(Date(client.lastConnectedAt)))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = cs.sub,
            )
        }
        TextButton(
            onClick = { confirm = true },
            colors = ButtonDefaults.textButtonColors(contentColor = cs.accent),
        ) {
            Text("Revoke", style = MaterialTheme.typography.labelMedium)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Revoke ${client.label}?") },
            text = {
                Text(
                    "This computer will need to be approved again the next time it connects.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onRevoke()
                }) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}
