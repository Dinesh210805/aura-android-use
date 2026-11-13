package com.aura.aura_ui.presentation.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.agent.conversation.PersonaOwner
import com.aura.aura_ui.agentcore.Avatar
import com.aura.aura_ui.agentcore.LogKeep
import com.aura.aura_ui.agentcore.PhoneAccess
import com.aura.aura_ui.agentcore.rememberPhoneAccess
import androidx.compose.material.icons.outlined.AccessibilityNew
import com.aura.aura_ui.agentcore.Profile
import com.aura.aura_ui.data.preferences.DiagnosticsStore
import com.aura.aura_ui.data.preferences.ThemeManager
import com.aura.aura_ui.presentation.components.AuraDivider
import com.aura.aura_ui.presentation.components.AuraPage
import com.aura.aura_ui.presentation.components.AuraRow
import com.aura.aura_ui.presentation.components.AuraSection
import com.aura.aura_ui.presentation.components.AuraToggleRow
import com.aura.aura_ui.presentation.navigation.AuraRoutes
import com.aura.aura_ui.remote.AuraSignIn
import com.aura.aura_ui.remote.SignInOutcome
import com.aura.aura_ui.services.WakeWordListeningService
import com.aura.aura_ui.ui.theme.AppearanceStore
import com.aura.aura_ui.ui.theme.AuraType
import com.aura.aura_ui.ui.theme.LocalAura
import com.aura.aura_ui.ui.theme.auraCard
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

/** Inset of a divider between rows that have an icon. */
private val ICON_INSET = 52.dp

/**
 * Settings: the account, then General, AI, Privacy and security, Connections, About.
 *
 * - Contract: every row opens a page or flips one switch. A subtitle appears only where the
 *   effect is not obvious from the title. Agent behaviour lives on the On-device agent page.
 *
 * @param onOpen opens a route in [AuraRoutes].
 * @param debug shows the Developer section (the eval suite), debug builds only.
 */
@Composable
fun MonoSettingsScreen(onOpen: (String) -> Unit, debug: Boolean = false) {
    val context = LocalContext.current
    remember(context) { DiagnosticsStore.hydrate(context) }
    val diagnostics by DiagnosticsStore.enabled.collectAsState()
    val wakeWord by ThemeManager.enableWakeWord.collectAsState()
    val haptics by ThemeManager.enableHapticFeedback.collectAsState()
    val edgeGlow by ThemeManager.enableEdgeGlow.collectAsState()
    val notifications by ThemeManager.enableNotifications.collectAsState()
    val style by AppearanceStore.style.collectAsState()

    AuraPage("Settings", orbits = true) {
        ProfileCard()

        AuraSection(title = "General") {
            AuraRow("Appearance", icon = Icons.Outlined.Palette, value = style.title, onClick = { onOpen(AuraRoutes.SETTINGS_APPEARANCE) })
            AuraDivider(ICON_INSET)
            AuraToggleRow("Notifications", notifications, ThemeManager::updateNotifications, icon = Icons.Outlined.Notifications)
            AuraDivider(ICON_INSET)
            AuraToggleRow("Haptics", haptics, ThemeManager::updateHapticFeedback, icon = Icons.Outlined.Vibration)
            AuraDivider(ICON_INSET)
            AuraToggleRow("Edge lighting", edgeGlow, ThemeManager::updateEdgeGlow, icon = Icons.Outlined.BlurOn, subtitle = "Glow around the screen while AURA works")
            AuraDivider(ICON_INSET)
            AuraToggleRow(
                "Wake word",
                wakeWord,
                { on ->
                    ThemeManager.updateWakeWord(on)
                    if (on) WakeWordListeningService.start(context) else WakeWordListeningService.stop(context)
                },
                icon = Icons.Outlined.Hearing,
                subtitle = "Say \"Wake up AURA\"",
            )
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                AuraDivider(ICON_INSET)
                AuraRow("Live updates", icon = Icons.Outlined.Notifications, onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                })
            }
        }

        AuraSection(title = "AI") {
            AuraRow("On-device agent", icon = Icons.Outlined.Hub, onClick = { onOpen(AuraRoutes.AGENT) })
            AuraDivider(ICON_INSET)
            AuraRow("Voice", icon = Icons.Outlined.RecordVoiceOver, onClick = { onOpen(AuraRoutes.AGENT_COMPANION) })
            AuraDivider(ICON_INSET)
            AuraRow("API keys", icon = Icons.Outlined.Key, onClick = { onOpen(AuraRoutes.apiKeys()) })
        }

        AuraSection(title = "Privacy and security") {
            val access = rememberPhoneAccess()
            AuraRow(
                "Accessibility",
                icon = Icons.Outlined.AccessibilityNew,
                value = if (access) "On" else "Off",
                tint = if (access) null else LocalAura.current.danger,
                onClick = { PhoneAccess.openSettings(context) },
            )
            AuraDivider(ICON_INSET)
            AuraRow("Permissions", icon = Icons.Outlined.Security, onClick = { onOpen(AuraRoutes.SETTINGS_PERMISSIONS) })
            AuraDivider(ICON_INSET)
            AuraRow("Restricted apps", icon = Icons.Outlined.Block, onClick = { onOpen(AuraRoutes.SETTINGS_RESTRICTED_APPS) })
            AuraDivider(ICON_INSET)
            val keep = remember { LogKeep.days(context) }
            AuraRow("Keep run logs", icon = Icons.Outlined.History, value = LogKeep.label(keep), onClick = { onOpen(AuraRoutes.RUN_LOGS_KEEP) })
            AuraDivider(ICON_INSET)
            AuraToggleRow(
                "Share diagnostics",
                diagnostics,
                { DiagnosticsStore.setEnabled(context, it) },
                icon = Icons.Outlined.Insights,
                subtitle = "Crash reports and usage counts. Never your tasks, screen or messages.",
            )
        }

        AuraSection(title = "Connections") {
            AuraRow("MCP server", icon = Icons.Outlined.Hub, onClick = { onOpen(AuraRoutes.SETTINGS_MCP_CENTER) })
            AuraDivider(ICON_INSET)
            AuraRow("Trusted devices", icon = Icons.Outlined.Devices, onClick = { onOpen(AuraRoutes.SETTINGS_TRUSTED_DEVICES) })
            AuraDivider(ICON_INSET)
            AuraRow("Phone status", icon = Icons.Outlined.MonitorHeart, onClick = { onOpen(AuraRoutes.HOME_DASHBOARD) })
        }

        AuraSection(title = "About") {
            val version = remember { appVersion(context) }
            AuraRow("Version", icon = Icons.Outlined.Info, value = version)
            AuraDivider(ICON_INSET)
            AuraRow("Join the Discord", icon = Icons.Outlined.Forum, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
            AuraDivider(ICON_INSET)
            AuraRow("Privacy policy", icon = Icons.Outlined.Shield, onClick = { onOpen(AuraRoutes.SETTINGS_PRIVACY) })
            AuraDivider(ICON_INSET)
            AuraRow("Terms of service", icon = Icons.Outlined.Description, onClick = { onOpen(AuraRoutes.SETTINGS_TERMS) })
            AuraDivider(ICON_INSET)
            AuraRow("Open-source licences", icon = Icons.Outlined.Code, onClick = { onOpen(AuraRoutes.SETTINGS_LICENSES) })
        }

        // The eval suite spends real provider quota running the whole task list: debug builds only.
        if (debug) {
            AuraSection(title = "Developer") {
                AuraRow("Eval suite", icon = Icons.Outlined.Science, onClick = { onOpen(AuraRoutes.EVAL_SUITE) })
            }
        }
    }
}

/**
 * The Google account: photo, name and email. Tapping it signs in when signed out.
 * Why no sign-out: the account is what makes the install usable, so a sign-out row would be a
 * one-tap way to lock yourself out of your own assistant.
 */
@Composable
private fun ProfileCard() {
    val context = LocalContext.current
    val aura = LocalAura.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val profile = remember(tick) {
        runCatching {
            FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous }?.let {
                Profile(it.displayName ?: PersonaOwner.get(context), it.email, it.photoUrl?.toString())
            }
        }.getOrNull() ?: Profile()
    }
    val signedIn = profile.email != null
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).auraCard().clickable(enabled = !signedIn) {
            scope.launch {
                when (val out = AuraSignIn(context).signIn(context)) {
                    is SignInOutcome.Success -> tick++
                    SignInOutcome.Cancelled -> Unit
                    SignInOutcome.NoGoogleAccount -> Toast.makeText(context, "Add a Google account in Android Settings first.", Toast.LENGTH_LONG).show()
                    is SignInOutcome.Misconfigured -> Toast.makeText(context, out.message, Toast.LENGTH_LONG).show()
                    is SignInOutcome.Failed -> Toast.makeText(context, out.message, Toast.LENGTH_LONG).show()
                }
            }
        }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile, 56)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(profile.name ?: if (signedIn) profile.email.orEmpty() else "Sign in", style = AuraType.title, color = aura.label)
            Text(profile.email ?: "With Google, to use AURA", style = AuraType.callout, color = aura.secondary)
        }
    }
}

/** The AURA community server: chat, reviews and bug reports. */
private const val DISCORD_INVITE = "https://discord.com/invite/H66ws9zMPF"

private fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    "${info.versionName ?: "—"} ($code)"
}.getOrDefault("—")
