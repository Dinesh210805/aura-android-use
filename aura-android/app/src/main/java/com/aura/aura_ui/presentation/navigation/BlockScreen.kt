package com.aura.aura_ui.presentation.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.aura_ui.presentation.components.AuraButton
import com.aura.aura_ui.presentation.components.AuraLogoBubble
import com.aura.aura_ui.remote.GateState
import com.aura.aura_ui.remote.UpdateProgress
import com.aura.aura_ui.ui.theme.AuraShape
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.auraCard
import com.aura.aura_ui.ui.theme.auraCardStrong
import com.aura.aura_ui.ui.theme.auraBackground

/** Release notes longer than this many lines fold behind "Show more". */
private const val NOTES_FOLDED_LINES = 4

/**
 * Full-screen, non-dismissible page shown instead of the whole app while the remote gate blocks.
 *
 * - Rendered by [AuraAppShell] for `GateState.KillSwitchBlocked` (the owner turned AURA off, or
 *   this device is blocked: [updateUrl] is null, the user can only wait) and
 *   `GateState.VersionBlocked` (this version is below the floor: [UpdateActions] installs it).
 * - `GateEnforcer.openBlockScreen` brings the user here when they try to start AURA from outside
 *   the app (wake word, volume shortcut, assist gesture).
 * - Blank [updateUrl] and [apkUrl] for a version block still tell the user what to do.
 */
@Composable
fun BlockScreen(
    title: String,
    message: String,
    updateUrl: String?,
    apkUrl: String = "",
    progress: UpdateProgress = UpdateProgress.Idle,
    onUpdate: (String) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val aura = LocalAura.current
    Box(Modifier.fillMaxSize().auraBackground().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().auraCard().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuraLogoBubble(size = 56.dp)
            Spacer(Modifier.height(18.dp))
            Text(title, style = AuraType.title, color = aura.label, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(message, style = AuraType.callout, color = aura.secondary, textAlign = TextAlign.Center)
            if (updateUrl != null) {
                Spacer(Modifier.height(22.dp))
                if (updateUrl.isNotBlank() || apkUrl.isNotBlank()) {
                    UpdateActions(updateUrl, apkUrl, progress, onUpdate, onCancel, onNotNow = null)
                } else {
                    Text("Install the latest version from wherever you got AURA.", style = AuraType.footnote, color = aura.tertiary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * The "update available" pop-up: the new version, its release notes folded after a few lines,
 * and the update buttons ([UpdateActions]).
 *
 * - Contract: [onDismiss] hides it for this app session; the gate shows it again next launch.
 *   It can't be dismissed while a download runs: Cancel stops it first.
 */
@Composable
fun UpdateDialog(
    state: GateState.UpdateAvailable,
    progress: UpdateProgress,
    onUpdate: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val aura = LocalAura.current
    var expanded by remember { mutableStateOf(false) }
    val lines = state.notes.trim().lines()
    val folded = !expanded && lines.size > NOTES_FOLDED_LINES
    val busy = progress is UpdateProgress.Downloading

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !busy),
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().auraCardStrong(AuraShape.Card).padding(22.dp).animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuraLogoBubble(size = 44.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Update available", style = AuraType.title, color = aura.label)
                    if (state.versionName.isNotBlank()) Text("Version ${state.versionName}", style = AuraType.footnote, color = aura.secondary)
                }
            }
            if (state.notes.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("What's new", style = AuraType.footnote, color = aura.secondary)
                Column(Modifier.padding(top = 6.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (folded) lines.take(NOTES_FOLDED_LINES).joinToString("\n") else state.notes.trim(),
                        style = AuraType.callout,
                        color = aura.label,
                    )
                }
                if (lines.size > NOTES_FOLDED_LINES) {
                    Text(
                        if (expanded) "Show less" else "Show more",
                        Modifier.clickable { expanded = !expanded }.padding(vertical = 8.dp),
                        style = AuraType.caption,
                        color = aura.label,
                    )
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text("A newer version of AURA is ready to install.", style = AuraType.callout, color = aura.secondary)
            }
            Spacer(Modifier.height(20.dp))
            UpdateActions(state.updateUrl, state.apkUrl, progress, onUpdate, onCancel, onNotNow = onDismiss)
        }
    }
}

/**
 * The buttons and status for one update, shared by [UpdateDialog] and [BlockScreen].
 *
 * - Contract: with an [apkUrl], Update installs in the app ([onUpdate], `AppUpdater`); without
 *   one it opens [updateUrl] in the browser. "Download in browser" appears only with an
 *   [updateUrl]. [onNotNow] null hides Not now (the forced block screen).
 */
@Composable
private fun UpdateActions(
    updateUrl: String,
    apkUrl: String,
    progress: UpdateProgress,
    onUpdate: (String) -> Unit,
    onCancel: () -> Unit,
    onNotNow: (() -> Unit)?,
) {
    val aura = LocalAura.current
    val context = LocalContext.current
    val update: () -> Unit = {
        if (apkUrl.isNotBlank()) {
            onUpdate(apkUrl)
        } else {
            openUrl(context, updateUrl)
            onNotNow?.invoke()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        when (progress) {
            UpdateProgress.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onNotNow != null) AuraButton("Not now", onNotNow, Modifier.weight(1f), primary = false)
                AuraButton("Update", update, Modifier.weight(1f))
            }
            UpdateProgress.NeedsInstallPermission -> {
                Status("Allow AURA to install updates, then tap Update again.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AuraButton("Allow", { openInstallPermission(context) }, Modifier.weight(1f), primary = false)
                    AuraButton("Update", update, Modifier.weight(1f))
                }
            }
            is UpdateProgress.Downloading -> {
                val percent = progress.percent
                if (percent == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = aura.accent, trackColor = aura.well)
                } else {
                    LinearProgressIndicator({ percent / 100f }, Modifier.fillMaxWidth(), color = aura.accent, trackColor = aura.well)
                }
                Status(if (percent == null) "Downloading…" else "Downloading $percent%")
                AuraButton("Cancel", onCancel, Modifier.fillMaxWidth(), primary = false)
            }
            UpdateProgress.WaitingForInstall -> {
                Status("Confirm the update on the next screen.")
                AuraButton("Cancel", onCancel, Modifier.fillMaxWidth(), primary = false)
            }
            is UpdateProgress.Failed -> {
                Status(progress.reason)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (updateUrl.isNotBlank()) {
                        AuraButton("Download in browser", { openUrl(context, updateUrl) }, Modifier.weight(1f), primary = false)
                    }
                    AuraButton("Try again", update, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        style = AuraType.footnote,
        color = LocalAura.current.secondary,
        textAlign = TextAlign.Center,
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Android's per-app "Install unknown apps" switch for AURA. */
private fun openInstallPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
