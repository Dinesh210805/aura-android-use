package com.aura.aura_ui.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import com.aura.aura_ui.presentation.components.AuraSwitch
import com.aura.aura_ui.presentation.components.CharcoalCard
import com.aura.aura_ui.data.preferences.DeveloperModeStore
import com.aura.aura_ui.mcp.McpConnectionRegistry
import com.aura.aura_ui.mcp.McpHealthRegistry
import com.aura.aura_ui.ui.theme.Capsule
import com.aura.aura_ui.ui.theme.CapsuleScheme
import com.aura.mcp.McpServerHealth
import java.text.DateFormat
import java.util.Date

/**
 * The MCP Center — one screen with everything related to the on-device MCP
 * server.
 *
 *   1. Status banner: the page's charcoal hero card — same treatment (size,
 *      accent circle, eyebrow + bold title) as the Agent hub's Brain card —
 *      state mirrors [McpServerHealth].
 *   2. Server details card: host, port, reachable IPs, fingerprint.
 *   3. Action row: Start/Stop (primary) + Restart (secondary).
 *   4. Keep-Alive toggle in its own row.
 *   5. Connected Clients section: live list of SSE sessions with a per-row
 *      Disconnect button. Empty state when nothing is connected.
 *   6. Developer mode toggle — reveals the `aura-adb` notes. Disclosure only;
 *      the tool is armed on the computer running the daemon, not here.
 *   7. Sub-rows for Pairing, Activity Log, Log Retention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpCenterScreen(
    onNavigateBack: () -> Unit,
    onOpenActivityLog: () -> Unit,
    onOpenRetention: () -> Unit,
    onOpenTrustedDevices: () -> Unit,
    onOpenRestrictedApps: () -> Unit,
) {
    val context = LocalContext.current
    val cs = Capsule.scheme()
    val health by McpHealthRegistry.health.collectAsState()
    val clients by McpConnectionRegistry.clients.collectAsState()
    val webRtcApprovalRequest by McpHealthRegistry.webRtcApprovalRequest.collectAsState()

    remember(context) { DeveloperModeStore.hydrate(context) }
    val developerMode by DeveloperModeStore.enabled.collectAsState()

    val servicePrefs = remember {
        context.getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE)
    }
    var keepAlive by remember {
        mutableStateOf(servicePrefs.getBoolean("keep_alive_background", false))
    }

    AuraScreenScaffold(title = "MCP", subtitle = "The on-device tool server") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {

            webRtcApprovalRequest?.let { request ->
                val origin = listOfNotNull(
                    request.host?.takeIf { it.isNotBlank() },
                    request.platform?.takeIf { it.isNotBlank() },
                ).joinToString("  ·  ")
                AlertDialog(
                    onDismissRequest = { request.onDeny() },
                    title = { Text("Approve this computer?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${request.clientName} v${request.version} wants to see and control this phone from your computer.")
                            Text(
                                request.verificationCode.chunked(3).joinToString(" "),
                                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(
                                "Your computer shows a code too. Approve only if both codes match. If they differ, someone may be intercepting the connection: tap Deny.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (origin.isNotBlank()) {
                                Text(
                                    origin,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "Approve only if you started this connection. It will be remembered and can reconnect silently until you revoke it in Trusted Devices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { request.onApprove() }) {
                            Text("Approve")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { request.onDeny() }) {
                            Text("Deny")
                        }
                    }
                )
            }

            StatusBanner(health, cs)

            ServerCard(
                health = health,
                cs = cs,
                onStartWebRtc = { McpHealthRegistry.requestStartWebRtc(context) },
                onStop = { McpHealthRegistry.requestStop(context) },
                onRestart = { McpHealthRegistry.requestRestart(context) },
            )

            KeepAliveCard(
                enabled = keepAlive,
                onToggle = { v ->
                    keepAlive = v
                    servicePrefs.edit().putBoolean("keep_alive_background", v).apply()
                },
            )

            ConnectedClientsSection(
                clients = clients,
                onDisconnect = { key -> McpConnectionRegistry.requestDisconnect(context, key) },
            )

            SectionHeader("DEVELOPER")
            DeveloperModeCard(
                enabled = developerMode,
                onToggle = { v -> DeveloperModeStore.setEnabled(context, v) },
            )
            AnimatedVisibility(visible = developerMode) {
                AdbToolCard(cs)
            }

            SectionHeader("MORE")
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column {
                    NavRow(
                        icon = Icons.Filled.Security,
                        title = "Trusted Devices",
                        subtitle = "Computers allowed to connect — revoke access",
                        onClick = onOpenTrustedDevices,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Filled.Block,
                        title = "Restricted Apps",
                        subtitle = "Apps AURA declines to act in — banking, payments, auth",
                        onClick = onOpenRestrictedApps,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Filled.History,
                        title = "Activity Log",
                        subtitle = "Tool calls, connections, screenshots",
                        onClick = onOpenActivityLog,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Filled.Schedule,
                        title = "Log Retention",
                        subtitle = "How long to keep session logs",
                        onClick = onOpenRetention,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Status banner ─────────────────────────────────────────────────────────

/**
 * The MCP page's charcoal hero card — the SAME focal-object treatment as the
 * Agent hub's Brain ("Model & provider") card: charcoal gradient, 40dp accent
 * circle icon, eyebrow + bold title, dim description. One charcoal object per
 * screen region, per the Capsule doctrine — on this page, it's the server.
 */
@Composable
private fun StatusBanner(health: McpServerHealth, cs: CapsuleScheme) {
    val (icon, label, sub) = when (health) {
        is McpServerHealth.Listening -> StatusSpec(
            Icons.Filled.Bolt,
            "Running", "Your AI app can connect now",
        )
        McpServerHealth.Starting -> StatusSpec(
            Icons.Filled.Schedule,
            "Starting…", "Getting ready — this takes a second",
        )
        McpServerHealth.Stopped -> StatusSpec(
            Icons.Filled.Stop,
            "Stopped", "AI apps on your computer can't reach this phone",
        )
        is McpServerHealth.Crashed -> StatusSpec(
            Icons.Filled.Warning,
            "Crashed", health.reason.take(80),
        )
    }
    CharcoalCard(cs = cs) {
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
                        icon,
                        contentDescription = null,
                        tint = cs.onAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "THE SERVER",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = cs.onCharDim,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = cs.onChar,
                    )
                }
                if (health is McpServerHealth.Listening) {
                    // Live dot — same accent as the Brain card's arrow affordance.
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(cs.accent),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onCharDim,
            )
        }
    }
}

private data class StatusSpec(
    val icon: ImageVector,
    val label: String,
    val sub: String,
)

// ── Server card ───────────────────────────────────────────────────────────

@Composable
private fun ServerCard(
    health: McpServerHealth,
    cs: CapsuleScheme,
    onStartWebRtc: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val isListening = health is McpServerHealth.Listening
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            if (health is McpServerHealth.Listening) {
                StepText(
                    "Connect an AI app on your computer — Claude Desktop, Claude Code, " +
                        "Cursor or VS Code — so it can see and control this phone. " +
                        "Do these steps on your computer. Tap any grey box to copy it.",
                    cs,
                )
                StepText(
                    "Done this before on this computer? Every step below is one-time. " +
                        "Just open your AI app and it reconnects by itself.",
                    cs,
                )

                val address = health.reachableAddresses.firstOrNull()
                if (address != null) {
                    DetailRow(Icons.Filled.Lan, "This phone", health.reachableAddresses.joinToString("  ·  "), mono = true)
                    Text(
                        "Your computer must be on the same Wi-Fi as this phone (or on its hotspot). " +
                            "Nothing is reachable from the internet. For access from elsewhere, " +
                            "install Tailscale on both devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.sub,
                    )
                } else {
                    Text(
                        "This phone isn't on a local network. Connect it to Wi-Fi (or turn on its " +
                            "hotspot and join it from your computer) to pair.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(8.dp))
                StepLabel("STEP 1 · INSTALL THE CONNECTOR (ONE TIME)", cs)
                StepText(
                    "You need Node.js 18 or newer — download it free from nodejs.org. " +
                        "Then open a terminal (Windows: PowerShell · Mac: Terminal) and run:",
                    cs,
                )
                CopyableCode(
                    text = "npm install -g aura-mcp-connect@latest",
                    clipLabel = "AURA Install Command",
                    cs = cs,
                )
                StepText("It takes about a minute — it downloads around 50 MB.", cs)

                Spacer(Modifier.height(8.dp))
                StepLabel("STEP 2 · PAIR THIS PHONE (ONE TIME)", cs)
                // Why the approve step lives here: the "Approve this computer?" dialog opens
                // during `aura-mcp pair`, and `pairPhone` (aura-mcp-connect/src/pair.js) saves
                // the pairing only after the phone approves.
                StepText(
                    "In the same terminal, run this. The number is your pairing code. " +
                        "This phone then asks \"Approve this computer?\" and shows a code — " +
                        "tap Approve only if your computer shows the same code.",
                    cs,
                )
                CopyableCode(
                    text = "aura-mcp pair ${health.webRtcPin.orEmpty()}",
                    clipLabel = "AURA Pair Command",
                    cs = cs,
                    caption = "The PIN works once and changes after each pairing",
                )
                if (address != null) {
                    CopyableCode(
                        text = "aura-mcp pair ${health.webRtcPin.orEmpty()} --host=$address",
                        clipLabel = "AURA Pair Command",
                        cs = cs,
                        caption = "If your computer can't find the phone, name its address",
                    )
                }

                Spacer(Modifier.height(8.dp))
                StepLabel("STEP 3 · ADD AURA TO YOUR AI APP (ONE TIME)", cs)
                StepText(
                    "Open your AI app's MCP settings and paste this in " +
                        "(Claude Desktop: Settings → Developer → Edit Config). " +
                        "Several apps can use this phone at the same time.",
                    cs,
                )
                val config = """
{
  "mcpServers": {
    "aura": { "command": "aura-mcp", "args": [] }
  }
}
                """.trimIndent()
                CopyableCode(
                    text = config,
                    clipLabel = "AURA MCP Config",
                    cs = cs,
                )
                StepText(
                    "Windows: if AURA doesn't show up in your app, change the aura line to " +
                        "\"command\": \"cmd\", \"args\": [\"/c\", \"aura-mcp\"]",
                    cs,
                )
                // The URL route only works while a daemon is up, and nothing restarts
                // it after a reboot — so it's the advanced option, said plainly.
                CopyableCode(
                    text = "http://127.0.0.1:4816/mcp",
                    clipLabel = "AURA Daemon URL",
                    cs = cs,
                    caption = "Advanced — connect by URL instead. Only works while " +
                        "`aura-mcp daemon` is running in a terminal; start it again " +
                        "after restarting your computer.",
                )

                Spacer(Modifier.height(8.dp))
                StepLabel("STEP 4 · CHECK IT WORKS", cs)
                StepText(
                    "Restart your AI app and ask it: \"What's on my phone screen?\" " +
                        "Your computer then appears under Connected below.",
                    cs,
                )
            } else if (health is McpServerHealth.Crashed) {
                Text(
                    health.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    when (health) {
                        McpServerHealth.Starting -> "The server is binding to its port. This usually takes under a second."
                        McpServerHealth.Stopped ->
                            "This lets an AI app on your computer — like Claude or Cursor — " +
                                "see and control this phone. Nothing can connect while it's off.\n\n" +
                                "Tap Start server to get a pairing code and step-by-step setup."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isListening) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Server")
                    }
                } else {
                    Button(
                        onClick = onStartWebRtc,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Bolt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Start server")
                    }
                }
                
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restart")
                }
            }
        }
    }
}

/** Eyebrow step label — same treatment as Provider Settings' EyebrowLabel. */
@Composable
private fun StepLabel(text: String, cs: CapsuleScheme) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
        color = cs.sub,
    )
}

/** Plain-language body text under a [StepLabel]. */
@Composable
private fun StepText(text: String, cs: CapsuleScheme) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = cs.sub)
}

/** Tap-to-copy monospace block in the Capsule chip-well style. */
@Composable
private fun CopyableCode(
    text: String,
    clipLabel: String,
    cs: CapsuleScheme,
    caption: String? = null,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = cs.sub)
        }
        Surface(
            color = cs.accentSoft,
            shape = Capsule.ShapeChipWell,
            modifier = Modifier.fillMaxWidth().clickable {
                copyToClipboard(context, clipLabel, text)
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            },
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = cs.ink,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp), tint = cs.accent)
            }
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            icon,
            null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            fontSize = if (mono) 12.sp else MaterialTheme.typography.bodyMedium.fontSize,
            modifier = Modifier.weight(1f)
        )
        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

// ── Keep-Alive card ───────────────────────────────────────────────────────

@Composable
private fun KeepAliveCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bolt,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Keep Alive", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (enabled) "Stays connected when you leave the AURA app."
                    else "Turn on to stay connected when you leave the AURA app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AuraSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

// ── Developer mode ────────────────────────────────────────────────────────

/**
 * Reveals developer-facing documentation. Named honestly in the subtitle: this
 * shows information, it does not grant a capability. See [DeveloperModeStore].
 */
@Composable
private fun DeveloperModeCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.DeveloperMode,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Developer mode", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (enabled) "Showing developer tools and setup notes."
                    else "Show developer tools and setup notes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AuraSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * Explains `aura-adb`. The copy is careful to put the enable switch where it
 * actually lives — on the computer running the daemon — because this phone has
 * no part in the call path and cannot gate it.
 */
@Composable
private fun AdbToolCard(cs: CapsuleScheme) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Terminal,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "Run ADB commands over MCP",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "The aura-mcp-connect daemon can expose one extra tool, aura-adb, " +
                    "to any MCP client connected to it. Your assistant can then " +
                    "install APKs, pull logcat, or run shell commands while you work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // Shell-neutral on purpose: `VAR=1 cmd` is bash-only and fails when
            // pasted into PowerShell or cmd, which is where most people will
            // paste it. The flag form works identically in every shell.
            CopyableCode(
                text = "aura-mcp daemon --enable-adb",
                clipLabel = "aura-adb",
                cs = cs,
                caption = "Run this on your computer (any shell)",
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "To arm it every time your editor starts AURA instead, add this " +
                    "to that client's MCP config for the aura server:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            CopyableCode(
                text = "\"env\": { \"AURA_MCP_ENABLE_ADB\": \"1\" }",
                clipLabel = "aura-adb env",
                cs = cs,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Needs aura-mcp-connect 0.9.0 or newer — run npm install -g " +
                    "aura-mcp-connect@latest first. An already-running daemon " +
                    "can't be armed, so stop it before starting the new one.\n\n" +
                    "Runs on your computer, not this phone. It uses your computer's own " +
                    "adb against a device already paired there over USB or Wireless " +
                    "debugging — separate from AURA's PIN pairing. Because the phone " +
                    "is not in that path, this switch only shows these notes; the " +
                    "tool is armed on the computer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Connected clients ─────────────────────────────────────────────────────

@Composable
private fun ConnectedClientsSection(
    clients: List<McpConnectionRegistry.Client>,
    onDisconnect: (String) -> Unit,
) {
    SectionHeader("CONNECTED · ${clients.size}")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        if (clients.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Computer,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Nothing connected yet.\nWhen your AI app connects, it shows up here " +
                        "with the computer's name — you can disconnect it anytime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column {
                clients.forEachIndexed { i, c ->
                    ClientRow(c, onDisconnect)
                    if (i < clients.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientRow(
    client: McpConnectionRegistry.Client,
    onDisconnect: (String) -> Unit,
) {
    var confirm by remember(client.sessionKey) { mutableStateOf(false) }
    val df = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Computer,
            null,
            // Capsule `ok` — the palette's single muted green, matching the
            // status banner's healthy state instead of a raw Tailwind green.
            tint = Capsule.scheme().ok,
            modifier = Modifier.size(22.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // Who: the client's reported name + version.
            Text(
                buildString {
                    append(client.agentLabel)
                    client.clientVersion?.takeIf { it.isNotBlank() }?.let { append("  ·  v$it") }
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            // Where: host machine + OS.
            val origin = listOfNotNull(
                client.host?.takeIf { it.isNotBlank() },
                client.platform?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (origin.isNotBlank()) {
                Text(
                    origin,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // How + when + which: transport, connect time, stable client id.
            Text(
                buildString {
                    append(client.transport)
                    append("  ·  since ")
                    append(df.format(Date(client.connectedAtMillis)))
                    append("  ·  id ")
                    append(client.tokenId)
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = { confirm = true },
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Filled.LinkOff, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Disconnect", style = MaterialTheme.typography.labelMedium)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect ${client.agentLabel}?") },
            text = {
                Text(
                    "Closes this client's session and stops the server. " +
                        "Tap Start server to accept connections again — a previously " +
                        "trusted client reconnects automatically.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDisconnect(client.sessionKey)
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Section header + nav row ──────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
