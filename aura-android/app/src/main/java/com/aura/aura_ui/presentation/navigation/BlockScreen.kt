package com.aura.aura_ui.presentation.navigation

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme

/**
 * Full-screen, non-dismissible page shown instead of the whole app while the remote gate blocks.
 *
 * - Rendered by [AuraAppShell] for `GateState.KillSwitchBlocked` (the owner turned AURA off, or
 *   this device is blocked: [updateUrl] is null, the user can only wait) and
 *   `GateState.VersionBlocked` (this version is below the floor: the button opens [updateUrl]).
 * - `GateEnforcer.openBlockScreen` brings the user here when they try to start AURA from outside
 *   the app (wake word, volume shortcut, assist gesture).
 * - A blank [updateUrl] for a version block still tells the user what to do.
 */
@Composable
fun BlockScreen(title: String, message: String, updateUrl: String?, scheme: MonoScheme) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.canvas)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = scheme.textPrimary,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = scheme.textPrimary,
            )
            if (updateUrl != null) {
                Spacer(Modifier.height(20.dp))
                if (updateUrl.isNotBlank()) {
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) }
                    }) {
                        Text("Get the update")
                    }
                } else {
                    Text(
                        text = "Install the latest version from wherever you got AURA.",
                        color = scheme.textPrimary,
                    )
                }
            }
        }
    }
}

/** Dismissible, shown once per app session on top of the normal nav content. */
@Composable
fun UpdateBanner(updateUrl: String, scheme: MonoScheme, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Mono.ShapeChip)
            .background(scheme.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("An update is available", color = scheme.textPrimary)
        Row {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) }
            }) { Text("Update") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
