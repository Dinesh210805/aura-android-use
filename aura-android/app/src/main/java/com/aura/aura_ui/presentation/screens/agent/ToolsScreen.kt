package com.aura.aura_ui.presentation.screens.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.aura_ui.audio.RecordableVoiceStore
import com.aura.aura_ui.data.preferences.DeveloperModeStore
import com.aura.aura_ui.uistream.UiStreamServer
import com.aura.aura_ui.uistream.UiStreamStore
import com.aura.aura_ui.presentation.components.AuraSwitch
import com.aura.aura_ui.presentation.components.MonoSegmented
import com.aura.aura_ui.presentation.components.MonoTopBar
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme
import com.aura.aura_ui.agent.ClientToolCatalog
import com.aura.aura_ui.data.preferences.ReadScreenImageStore
import com.aura.mcp.bridge.McpScope
import com.aura.mcp.server.McpToolCatalog

// ============================================================================
// TOOLS — the agent's full callable set, rendered straight from the catalogs so
// this screen can never drift from what's actually registered and keeps no local
// tool list. Device tools come from [McpToolCatalog] (scope + copy, joined from
// the policy scope map); always-on client tools (ask_user, set_plan, mark_step)
// come from [ClientToolCatalog] and show an "AGENT" tag. Add a tool → it appears
// here automatically.
// ============================================================================

/** How a tool is tagged in the list: device READ/WRITE scope, or a client-side AGENT tool. */
private enum class ToolTag { READ, WRITE, AGENT }

private data class UiTool(val name: String, val description: String, val tag: ToolTag)

/** Device tools (from the server catalog) + always-on client tools, name-sorted. */
private fun allUiTools(): List<UiTool> {
    val device = McpToolCatalog.entries.map {
        UiTool(it.name, it.description, if (it.scope == McpScope.WRITE) ToolTag.WRITE else ToolTag.READ)
    }
    val client = ClientToolCatalog.entries.map { UiTool(it.name, it.description, ToolTag.AGENT) }
    return (device + client).sortedBy { it.name }
}

@Composable
fun ToolsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()
    var filter by remember { mutableIntStateOf(0) } // 0 all · 1 read · 2 write
    val tools = remember { allUiTools() }
    val visible = when (filter) {
        1 -> tools.filter { it.tag == ToolTag.READ }
        2 -> tools.filter { it.tag == ToolTag.WRITE }
        else -> tools
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.canvas),
    ) {
        // Back is owned by the global capsule nav bar — no redundant top-bar back.
        MonoTopBar(
            title = "Tools",
            subtitle = "${tools.size} tools the agent can call",
            scheme = scheme,
        )

        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            MonoSegmented(
                options = listOf("All", "Read", "Write"),
                selectedIndex = filter,
                onSelect = { filter = it },
                scheme = scheme,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            itemsIndexed(visible, key = { _, t -> t.name }) { index, tool ->
                val shape = groupedShape(index, visible.size)
                Surface(shape = shape, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tool.name,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = scheme.textPrimary,
                                )
                                Text(
                                    text = tool.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.textSecondary,
                                )
                            }
                            ToolTagChip(tool.tag, scheme)
                        }
                        if (tool.name == "read_screen") ReadScreenImageRow(scheme)
                        if (index < visible.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .height(1.dp)
                                    .background(scheme.divider),
                            )
                        }
                    }
                }
            }
            // Footer, below the catalog: aura-adb is NOT one of these tools —
            // it lives on the computer running the aura-mcp daemon, so it can't
            // come from McpToolCatalog and must not be listed alongside them.
            item {
                Spacer(modifier = Modifier.height(24.dp))
                DeveloperModeSection(scheme = scheme)
            }
            item { Spacer(modifier = Modifier.height(28.dp)) }
        }
    }
}

/**
 * Developer-only: let a screen recorder capture AURA's voice.
 *
 * AURA normally plays as `USAGE_VOICE_COMMUNICATION` so the hardware echo canceller has a far-end
 * reference for barge-in. Android's playback-capture API refuses that usage outright (it is call
 * audio), which is why demo recordings come out silent. This trades the AEC for a capturable
 * stream — see [RecordableVoiceStore]. Off by default, and not in normal Settings, because a user
 * who flipped it by accident would get an assistant that interrupts itself.
 */
@Composable
private fun RecordableVoiceRow(scheme: MonoScheme) {
    val context = LocalContext.current
    val recordable by RecordableVoiceStore.enabled.collectAsState()

    Surface(shape = Mono.ShapeCard, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Recordable voice",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.textPrimary,
                )
                Text(
                    text = if (recordable) {
                        "Screen recorders can capture AURA's voice. Barge-in is degraded — it may " +
                            "interrupt itself, because it now hears its own speech. Turn this off " +
                            "when you are done recording."
                    } else {
                        "AURA's voice plays as call audio, which Android never lets a screen " +
                            "recorder capture. Turn this on to record a demo."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recordable) Mono.Blood else scheme.textSecondary,
                )
            }
            AuraSwitch(checked = recordable, onCheckedChange = {
                RecordableVoiceStore.setEnabled(context, it)
            })
        }
    }
}

/**
 * Developer-mode footer. The toggle is a disclosure preference shared with the
 * MCP Center (see [DeveloperModeStore]) — flipping it here shows the same notes
 * there. It grants nothing: `aura-adb` runs on the computer hosting the daemon.
 */
@Composable
private fun DeveloperModeSection(scheme: MonoScheme) {
    val context = LocalContext.current
    remember(context) { DeveloperModeStore.hydrate(context) }
    val enabled by DeveloperModeStore.enabled.collectAsState()

    Column {
        Text(
            text = "DEVELOPER",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            ),
            color = scheme.textSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        Surface(shape = Mono.ShapeCard, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Developer mode",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = scheme.textPrimary,
                    )
                    Text(
                        text = "Show developer tools and setup notes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.textSecondary,
                    )
                }
                AuraSwitch(checked = enabled, onCheckedChange = {
                    DeveloperModeStore.setEnabled(context, it)
                })
            }
        }

        AnimatedVisibility(visible = enabled) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = Mono.ShapeCard,
                    color = scheme.card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "aura-adb",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = scheme.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "You can now use this MCP connection to run adb " +
                                "commands from any connected client — a second, " +
                                "independent channel to this phone. Agents are told " +
                                "to use it when gestures mis-target or a screen " +
                                "exposes no usable tree, to read dumpsys/logcat " +
                                "instead of retrying blindly, and never to route " +
                                "around a blocked action with it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.textSecondary,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        // Shell-neutral: `VAR=1 cmd` is bash-only and fails in
                        // PowerShell/cmd, where this is most likely to be pasted.
                        Surface(shape = Mono.ShapeChip, color = scheme.chip) {
                            Text(
                                text = "aura-mcp daemon --enable-adb",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = scheme.textPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Not one of the tools above: it runs on the computer " +
                                "hosting the aura-mcp daemon, using that machine's adb " +
                                "against a phone already paired there over USB or " +
                                "Wireless debugging. Arm it there with the command " +
                                "above — this switch only reveals these notes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.textSecondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                RecordableVoiceRow(scheme)
                Spacer(modifier = Modifier.height(8.dp))
                UiStreamRow(scheme)
            }
        }
    }
}

/**
 * Live UI-tree stream. Sits inside the developer disclosure because it publishes every
 * label on screen to anything attached to the port — that has to be a deliberate act,
 * not a default.
 *
 * It is NOT part of the agent's perception path; `perceive_screen` is unchanged. This
 * exists so a live tree, and an honest idle verdict, can be watched from a computer
 * while the phone is used normally.
 */
@Composable
private fun UiStreamRow(scheme: MonoScheme) {
    val context = LocalContext.current
    remember(context) { UiStreamStore.hydrate(context) }
    val on by UiStreamStore.enabled.collectAsState()

    Surface(shape = Mono.ShapeCard, color = scheme.card, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "UI tree stream",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = scheme.textPrimary,
                    )
                    Text(
                        text = if (on) {
                            "Streaming on 127.0.0.1:${UiStreamServer.DEFAULT_PORT} — " +
                                "${UiStreamServer.shared?.clientCount ?: 0} client(s), " +
                                "${UiStreamServer.shared?.framesSent ?: 0} frames sent."
                        } else {
                            "Push the live accessibility tree, with an idle flag, to a " +
                                "computer over adb. Off by default."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.textSecondary,
                    )
                }
                AuraSwitch(checked = on, onCheckedChange = { UiStreamStore.setEnabled(context, it) })
            }
            AnimatedVisibility(visible = on) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(shape = Mono.ShapeChip, color = scheme.chip) {
                        Text(
                            text = "adb forward tcp:${UiStreamServer.DEFAULT_PORT} " +
                                "tcp:${UiStreamServer.DEFAULT_PORT}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = scheme.textPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Newline-delimited JSON, one line per frame. Bound to " +
                            "loopback only, so adb is the only way in. Frames keep " +
                            "coming during animation; each carries idle=true/false so a " +
                            "consumer can wait for a still screen before acting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.textSecondary,
                    )
                }
            }
        }
    }
}

/** `read_screen`'s image mode, shown under that tool's own row. */
@Composable
private fun ReadScreenImageRow(scheme: MonoScheme) {
    val context = LocalContext.current
    val on by ReadScreenImageStore.enabled.collectAsState()
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Annotated screenshot — same numbers as the grid, colour-coded boxes. " +
                "Uses more tokens per look.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.textSecondary,
            modifier = Modifier.weight(1f),
        )
        AuraSwitch(checked = on, onCheckedChange = { ReadScreenImageStore.setEnabled(context, it) })
    }
}

/** Rounded top corners on the first row, bottom on the last — one visual card. */
private fun groupedShape(index: Int, count: Int) = when {
    count == 1 -> Mono.ShapeCard
    index == 0 -> androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp,
    )
    index == count - 1 -> androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = 0.dp, topEnd = 0.dp, bottomStart = 24.dp, bottomEnd = 24.dp,
    )
    else -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
}

/**
 * Tag chip: WRITE is the emphasised ink-filled pill (device-mutating), READ is a
 * quiet filled chip, AGENT (client-side control tools) is an outlined chip so it
 * reads as a different class from the device scopes.
 */
@Composable
private fun ToolTagChip(tag: ToolTag, scheme: MonoScheme) {
    val label = when (tag) {
        ToolTag.READ -> "READ"
        ToolTag.WRITE -> "WRITE"
        ToolTag.AGENT -> "AGENT"
    }
    val filledInk = tag == ToolTag.WRITE
    Surface(
        shape = Mono.ShapeChip,
        color = when (tag) {
            ToolTag.WRITE -> if (scheme.isDark) androidx.compose.ui.graphics.Color.White else Mono.Ink
            else -> scheme.chip
        },
        border = if (tag == ToolTag.AGENT) {
            androidx.compose.foundation.BorderStroke(1.dp, scheme.outline)
        } else {
            null
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (filledInk) {
                if (scheme.isDark) Mono.Ink else Mono.TextOnInk
            } else {
                scheme.textSecondary
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
