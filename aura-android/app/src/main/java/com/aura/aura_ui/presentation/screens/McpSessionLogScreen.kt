package com.aura.aura_ui.presentation.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.mcp.log.AgentRunLogger
import com.aura.aura_ui.mcp.log.LiveConversationLogger
import com.aura.aura_ui.mcp.log.McpSessionStore
import com.aura.aura_ui.mcp.log.SessionLog
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Phase 10B — list of past MCP sessions, newest first. The user's audit trail:
 * who connected (agent label), when, what tools they ran, and a screenshot thumb
 * so an entry is recognisable at a glance. Mono-styled; back is owned by the
 * global capsule nav bar.
 */
@Composable
fun McpSessionLogScreen(
    onNavigateBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenRetention: () -> Unit,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val store = remember { McpSessionStore(context) }
    var sessions by remember { mutableStateOf<List<SessionLog>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        sessions = withContext(Dispatchers.IO) { store.listSessions() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.canvas),
    ) {
        // Header: title + subtitle on the left, retention-settings gear on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Logs",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.8).sp,
                    ),
                    color = scheme.textPrimary,
                )
                Text(
                    text = if (sessions.isEmpty()) "Session audit trail" else "${sessions.size} session${if (sessions.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                )
            }
            Surface(
                onClick = onOpenRetention,
                shape = Mono.ShapePill,
                color = scheme.chip,
                border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = scheme.textPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "Retention",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = scheme.textPrimary,
                    )
                }
            }
        }

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No logs yet.\nTalk to AURA, run an on-device agent task, or connect an MCP agent — traces show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.textSecondary,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sessions, key = { it.sessionId }) { session ->
                SessionCard(
                    session = session,
                    scheme = scheme,
                    thumbProvider = { store.screenshotFile(session.sessionId, 0) },
                    onClick = { onOpenSession(session.sessionId) },
                    onDelete = {
                        store.deleteSession(session.sessionId)
                        refreshTick++
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Monochrome source chip. Three planes now write sessions, so this is keyed off the
 * source string rather than an `isAgent` boolean: ink-filled for AGENT runs and LIVE
 * conversations (both are AURA acting), hairline-outlined for external MCP clients.
 */
@Composable
private fun SourceChip(source: String, scheme: MonoScheme) {
    val label = when (source) {
        AgentRunLogger.SOURCE_AGENT -> "AGENT"
        LiveConversationLogger.SOURCE_LIVE -> "LIVE"
        else -> "MCP"
    }
    val filled = source == AgentRunLogger.SOURCE_AGENT || source == LiveConversationLogger.SOURCE_LIVE
    Surface(
        color = if (filled) Mono.Ink else scheme.chip,
        shape = Mono.ShapeChip,
        border = if (filled) null else androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = if (filled) Mono.TextOnInk else scheme.textSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionLog,
    scheme: MonoScheme,
    thumbProvider: () -> java.io.File?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // Each plane is recognisable by a different thing: an agent run by the command the user
    // gave, an MCP session by its first tool, and a conversation by its opening line. Falling
    // through to the tool name is what labelled every Live session "(no calls)".
    val title = when (session.source) {
        AgentRunLogger.SOURCE_AGENT -> session.command?.takeIf { it.isNotBlank() } ?: "Agent run"
        LiveConversationLogger.SOURCE_LIVE ->
            session.utterances.firstOrNull { it.kind == "speech" || it.kind == "text" }
                ?.text?.takeIf { it.isNotBlank() } ?: "Live conversation"
        else -> session.invocations.firstOrNull()?.toolName ?: "(no calls)"
    }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val thumb = remember(session.sessionId) {
        thumbProvider()?.takeIf { it.exists() }?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
    }
    var showConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Mono.ShapeCard)
            .clickable { onClick() },
        shape = Mono.ShapeCard,
        color = scheme.card,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 88.dp)
                    .clip(Mono.ShapeChip)
                    .background(scheme.chip),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("—", color = scheme.textSecondary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceChip(session.source, scheme)
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.textPrimary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    session.agentLabel ?: "(unknown agent)",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                )
                Text(
                    df.format(Date(session.startedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    (session.utterances.size.takeIf { it > 0 }?.let { "$it turn(s) · " } ?: "") +
                        "${session.invocations.size} tool(s)" +
                        (session.llmCalls.size.takeIf { it > 0 }?.let { " · $it LLM" } ?: "") +
                        (session.endReason?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.textSecondary,
                )
            }
            IconButton(onClick = { showConfirm = true }) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete session",
                    tint = Mono.Blood,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete session?") },
            text = { Text("This removes the metadata and all captured screenshots. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onDelete() }) {
                    Text("Delete", color = Mono.Blood)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
