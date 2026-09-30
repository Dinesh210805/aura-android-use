package com.aura.aura_ui.presentation.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.runtime.rememberCoroutineScope
import com.aura.aura_ui.agent.conversation.PersonaOwner
import com.aura.aura_ui.remote.AuraSignIn
import com.aura.aura_ui.remote.AuthState
import com.aura.aura_ui.remote.SignInOutcome
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aura.aura_ui.accessibility.AccessibilityCapturePolicy
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.data.preferences.DiagnosticsStore
import com.aura.aura_ui.data.preferences.ThemeManager
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import com.aura.aura_ui.presentation.components.AuraSwitch
import com.aura.aura_ui.presentation.components.MonoCardGroup
import com.aura.aura_ui.presentation.components.MonoDivider
import com.aura.aura_ui.presentation.components.MonoRow
import com.aura.aura_ui.presentation.components.MonoSectionLabel
import com.aura.aura_ui.presentation.components.PermissionToggleRow
import com.aura.aura_ui.presentation.components.permissionIcon
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.presentation.permissions.PermissionRegistry
import com.aura.aura_ui.presentation.permissions.openPermissionSystemPage
import com.aura.aura_ui.services.WakeWordListeningService
import com.aura.aura_ui.ui.theme.Mono
import com.aura.aura_ui.ui.theme.MonoScheme

// ============================================================================
// SETTINGS — app plumbing only (spec rule: agent-behavior lives in the Agent
// tab). Built on the shared AuraScreenScaffold + grouped Apple-style cards so
// it is visibly the same system as every other screen. Top-to-bottom:
//   Profile (Guest placeholder) → Access (uniform permission toggles + screen
//   capture) → Integrations → Appearance → Preferences → Privacy & Data → About.
// Every permission and preference uses the single AuraSwitch.
// ============================================================================

@Composable
fun MonoSettingsScreen(
    onOpenPermissions: () -> Unit,
    onOpenTavilyKey: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenRestrictedApps: () -> Unit = {},
    /**
     * Debug builds only. Null in release, which is what keeps the eval cockpit out of a shipped
     * app: the row cannot render if the callback does not exist, so there is no way to reach the
     * screen even if the route were somehow registered.
     */
    onOpenEvalSuite: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember(context) { DiagnosticsStore.hydrate(context) }
    val diagnosticsEnabled by DiagnosticsStore.enabled.collectAsState()
    val wakeWordEnabled by ThemeManager.enableWakeWord.collectAsState()
    val haptics by ThemeManager.enableHapticFeedback.collectAsState()
    val edgeGlow by ThemeManager.enableEdgeGlow.collectAsState()
    val notifications by ThemeManager.enableNotifications.collectAsState()
    val screenCaptureAvailable by AuraAccessibilityService.screenCaptureAvailable.collectAsState()

    // Re-evaluate permission statuses whenever the screen resumes (e.g. after the
    // user returns from a system settings page having granted something).
    var refresh by remember { mutableIntStateOf(0) }
    // Bumped when a sign-in completes, so the profile row re-reads without waiting for a resume.
    var signInTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    AuraScreenScaffold(title = "Settings", modifier = modifier) {
        // ── Profile — the real account, not a placeholder ────────────────────
        // `refresh` is already bumped on every ON_RESUME by the lifecycle observer above, so
        // returning from the account picker re-reads Firebase Auth without extra plumbing.
        val signedInEmail = remember(refresh, signInTick) { AuthState.email() }
        val displayName = remember(refresh, signInTick) {
            PersonaOwner.get(context) ?: signedInEmail?.substringBefore('@')
        }
        ProfileHeaderCard(
            scheme = scheme,
            name = displayName ?: "Guest",
            subtitle = signedInEmail ?: "Not signed in — tap to sign in with Google",
            signedIn = signedInEmail != null,
        ) {
            if (signedInEmail != null) {
                // Signing out is not offered here. The account is what makes the install
                // usable at all, so a sign-out button in Settings is a one-tap way to lock
                // yourself out of your own phone assistant.
                Toast.makeText(context, "Signed in as $signedInEmail", Toast.LENGTH_SHORT).show()
            } else {
                scope.launch {
                    when (val out = AuraSignIn(context).signIn(context)) {
                        is SignInOutcome.Success -> signInTick++
                        SignInOutcome.Cancelled -> Unit
                        SignInOutcome.NoGoogleAccount -> Toast.makeText(
                            context,
                            "No Google account on this phone. Add one in Android Settings.",
                            Toast.LENGTH_LONG,
                        ).show()
                        is SignInOutcome.Misconfigured ->
                            Toast.makeText(context, out.message, Toast.LENGTH_LONG).show()
                        is SignInOutcome.Failed ->
                            Toast.makeText(context, out.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // ── Access — uniform permission toggles ──────────────────────────────
        MonoSectionLabel("Access", scheme)
        MonoCardGroup(scheme) {
            PermissionRegistry.tier1.forEachIndexed { index, permission ->
                if (index > 0) MonoDivider()
                val granted = remember(permission.id, refresh) { permission.isGranted(context) }
                PermissionToggleRow(
                    permission = permission,
                    granted = granted,
                    icon = permissionIcon(permission.id),
                    onRequestRuntime = { runtimeLauncher.launch(it.toTypedArray()) },
                    onOpenSystem = {
                        openPermissionSystemPage(context, permission, onScreenCapture = onRequestScreenCapture)
                    },
                )
            }
            // HIDDEN 2026-08-12 — kept, not deleted, so the screen-mirroring feature can
            // restore it by flipping AccessibilityCapturePolicy.MIRROR_ENABLED.
            //
            // The agent no longer needs MediaProjection to see the screen: perception moved to
            // AccessibilityService.takeScreenshot(), covered by the Accessibility permission
            // already granted above. Showing this switch would ask for a capability nothing
            // consumes — and because the mirror is gated off, it could never turn ON, leaving
            // a switch that visibly refuses to move.
            if (AccessibilityCapturePolicy.MIRROR_ENABLED) {
                MonoDivider()
                MonoRow(
                    icon = Icons.Outlined.Screenshot,
                    title = "Screen capture",
                    subtitle = if (screenCaptureAvailable) "Enabled for this session" else "Let the agent see the screen",
                    trailing = {
                        AuraSwitch(
                            checked = screenCaptureAvailable,
                            onCheckedChange = { enabled ->
                                if (enabled && !screenCaptureAvailable) onRequestScreenCapture()
                            },
                        )
                    },
                )
            }
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.Security,
                title = "See all permissions",
                subtitle = "Manage everything AURA can access",
                onClick = onOpenPermissions,
            )
        }

        // ── Safety ───────────────────────────────────────────────────────────
        MonoSectionLabel("Safety", scheme)
        MonoCardGroup(scheme) {
            MonoRow(
                icon = Icons.Outlined.Block,
                title = "Restricted Apps",
                subtitle = "Apps AURA declines to act in — banking, payments, auth",
                onClick = onOpenRestrictedApps,
            )
        }

        // ── Integrations ─────────────────────────────────────────────────────
        MonoSectionLabel("Integrations", scheme)
        MonoCardGroup(scheme) {
            MonoRow(
                icon = Icons.Outlined.Search,
                title = "Web search (Tavily)",
                subtitle = "API key for live web lookups",
                onClick = onOpenTavilyKey,
            )
        }

        // ── Preferences ──────────────────────────────────────────────────────
        MonoSectionLabel("Preferences", scheme)
        MonoCardGroup(scheme) {
            MonoRow(
                icon = Icons.Outlined.Hearing,
                title = "\"Hello AURA\" / \"Wake up AURA\" wake word",
                trailing = {
                    AuraSwitch(
                        checked = wakeWordEnabled,
                        onCheckedChange = { enabled ->
                            ThemeManager.updateWakeWord(enabled)
                            if (enabled) {
                                WakeWordListeningService.start(context)
                            } else {
                                WakeWordListeningService.stop(context)
                            }
                        },
                    )
                },
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.Notifications,
                title = "Notifications",
                trailing = {
                    AuraSwitch(
                        checked = notifications,
                        onCheckedChange = ThemeManager::updateNotifications,
                    )
                },
            )
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                MonoDivider()
                MonoRow(
                    icon = Icons.Outlined.Notifications,
                    title = "Live Updates",
                    subtitle = "Manage status bar chip settings",
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
                )
            }
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.Vibration,
                title = "Haptic feedback",
                trailing = {
                    AuraSwitch(
                        checked = haptics,
                        onCheckedChange = ThemeManager::updateHapticFeedback,
                    )
                },
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.BlurOn,
                title = "Edge lighting",
                subtitle = "White glow around the screen while AURA is open or working",
                trailing = {
                    AuraSwitch(
                        checked = edgeGlow,
                        onCheckedChange = ThemeManager::updateEdgeGlow,
                    )
                },
            )
        }

        // ── Privacy & Data ───────────────────────────────────────────────────
        MonoSectionLabel("Privacy & Data", scheme)
        MonoCardGroup(scheme) {
            // Placed first in this group, above the policy links: a control the
            // user can act on outranks documents they can only read.
            MonoRow(
                icon = Icons.Outlined.Insights,
                title = "Share anonymous diagnostics",
                subtitle = "Phone model, Android and AURA version, crash reports. " +
                    "Never your tasks, screen or messages.",
                trailing = {
                    AuraSwitch(
                        checked = diagnosticsEnabled,
                        onCheckedChange = { DiagnosticsStore.setEnabled(context, it) },
                    )
                },
            )
            MonoDivider()
            MonoRow(icon = Icons.Outlined.Shield, title = "Privacy Policy", onClick = onOpenPrivacy)
            MonoDivider()
            MonoRow(icon = Icons.Outlined.Description, title = "Terms of Service", onClick = onOpenTerms)
            MonoDivider()
            MonoRow(icon = Icons.Outlined.Code, title = "Open-source licenses", onClick = onOpenLicenses)
        }

        // ── Developer (debug builds only) ────────────────────────────────────
        // The eval cockpit drives the agent through the whole task suite and spends real provider
        // quota doing it, so it lives behind BuildConfig.DEBUG and is passed in as a nullable
        // callback rather than gated inside this file.
        onOpenEvalSuite?.let { openEval ->
            MonoSectionLabel("Developer", scheme)
            MonoCardGroup(scheme) {
                MonoRow(icon = Icons.Outlined.Science, title = "Eval suite", onClick = openEval)
            }
        }

        // ── About ────────────────────────────────────────────────────────────
        MonoSectionLabel("About", scheme)
        MonoCardGroup(scheme) {
            val (versionName, versionCode) = remember { appVersion(context) }
            MonoRow(
                icon = Icons.Outlined.Info,
                title = "Version",
                trailing = {
                    Text(
                        text = versionName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.textSecondary,
                    )
                },
            )
            MonoDivider()
            MonoRow(
                icon = Icons.Outlined.Info,
                title = "Build",
                trailing = {
                    Text(
                        text = versionCode,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.textSecondary,
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/** Apple-ID-style account card. Placeholder identity until OAuth is wired. */
@Composable
private fun ProfileHeaderCard(
    scheme: MonoScheme,
    name: String,
    subtitle: String,
    signedIn: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = Mono.ShapeCard,
        color = scheme.card,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(scheme.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (signedIn) Icons.Outlined.Verified else Icons.Outlined.Person,
                    contentDescription = null,
                    tint = scheme.chipContent,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.textSecondary,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun appVersion(context: Context): Pair<String, String> = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode.toString()
    } else {
        @Suppress("DEPRECATION") info.versionCode.toString()
    }
    (info.versionName ?: "—") to code
} catch (e: Exception) {
    "—" to "—"
}
