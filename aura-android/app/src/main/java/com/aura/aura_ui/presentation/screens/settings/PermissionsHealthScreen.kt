package com.aura.aura_ui.presentation.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aura.aura_ui.presentation.components.MonoCardGroup
import com.aura.aura_ui.presentation.components.MonoDivider
import com.aura.aura_ui.presentation.components.MonoSectionLabel
import com.aura.aura_ui.presentation.components.PermissionToggleRow
import com.aura.aura_ui.presentation.components.AuraScreenScaffold
import com.aura.aura_ui.presentation.components.permissionIcon
import com.aura.aura_ui.presentation.components.rememberMonoScheme
import com.aura.aura_ui.presentation.permissions.AuraPermission
import com.aura.aura_ui.presentation.permissions.PermissionRegistry
import com.aura.aura_ui.presentation.permissions.openPermissionSystemPage

// ============================================================================
// PERMISSIONS HEALTH — every permission / special access AURA can use, each as
// a uniform toggle (PermissionToggleRow). Runtime perms flip in place; special
// access routes to the right system page. Statuses re-check on ON_RESUME (i.e.
// when the user returns from a system settings page). Built on the shared
// AuraScreenScaffold so it matches Settings exactly (spec v2 §2).
// ============================================================================

@Composable
fun PermissionsHealthScreen(
    onNavigateBack: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bump to force status re-evaluation after returning from system settings.
    var refresh by remember { mutableIntStateOf(0) }
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

    fun openSystemFor(p: AuraPermission) =
        openPermissionSystemPage(context, p, onScreenCapture = onRequestScreenCapture)

    AuraScreenScaffold(
        title = "Permissions",
        subtitle = "What AURA can use, and why",
        onBack = onNavigateBack,
        modifier = modifier,
    ) {
        MonoSectionLabel("Core — needed for AURA to work", scheme)
        MonoCardGroup(scheme) {
            PermissionRegistry.tier1.forEachIndexed { index, p ->
                if (index > 0) MonoDivider()
                val granted = remember(refresh) { p.isGranted(context) }
                PermissionToggleRow(
                    permission = p,
                    granted = granted,
                    icon = permissionIcon(p.id),
                    onRequestRuntime = { runtimeLauncher.launch(it.toTypedArray()) },
                    onOpenSystem = { openSystemFor(p) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        MonoSectionLabel("Capabilities — asked when first needed", scheme)
        // Screen capture is managed from the Settings screen (its own working
        // MediaProjection control); the row here never worked, so it's filtered
        // out. Registry entry is intentionally left intact.
        val capabilities = PermissionRegistry.tier2.filter { it.id != "screen_capture" }
        MonoCardGroup(scheme) {
            capabilities.forEachIndexed { index, p ->
                if (index > 0) MonoDivider()
                val granted = remember(refresh) { p.isGranted(context) }
                PermissionToggleRow(
                    permission = p,
                    granted = granted,
                    icon = permissionIcon(p.id),
                    onRequestRuntime = { runtimeLauncher.launch(it.toTypedArray()) },
                    onOpenSystem = { openSystemFor(p) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
