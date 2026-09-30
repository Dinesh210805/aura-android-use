package com.aura.aura_ui.presentation.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.aura_ui.presentation.permissions.AuraPermission
import com.aura.aura_ui.presentation.permissions.ToggleAction
import com.aura.aura_ui.presentation.permissions.toggleAction
import com.aura.aura_ui.presentation.permissions.toggleUiState

// ============================================================================
// PERMISSION TOGGLE ROW — the uniform "switch on every permission" row.
//
// Runtime perms flip the OS grant; special-access perms route to the right
// system page (with an "Opens Android settings" caption). Logic lives in
// PermissionToggleModel; this file is presentation only.
// ============================================================================

@Composable
fun PermissionToggleRow(
    permission: AuraPermission,
    granted: Boolean,
    icon: ImageVector,
    onRequestRuntime: (List<String>) -> Unit,
    onOpenSystem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = rememberMonoScheme()
    val ui = toggleUiState(permission, granted)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = scheme.chipContent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    permission.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = scheme.textPrimary,
                )
                if (!granted) BloodDot(size = 6.dp)
            }
            Spacer(Modifier.height(2.dp))
            Text(permission.why, style = MaterialTheme.typography.bodySmall, color = scheme.textSecondary)
            if (ui.caption != null) {
                Spacer(Modifier.height(2.dp))
                Text(ui.caption, style = MaterialTheme.typography.labelSmall, color = scheme.textSecondary)
            }
            // Device-specific extra steps. Shown even when `granted` is true,
            // because on MIUI/ColorOS/Funtouch/EMUI the Android toggle reading
            // "on" is not proof the permission actually works — a second vendor
            // switch can still be suppressing the overlay.
            val context = LocalContext.current
            val oemNote = remember(permission.id) { permission.oemNote?.invoke(context) }
            if (oemNote != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    oemNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.textSecondary,
                )
            }
        }
        AuraSwitch(
            checked = ui.checked,
            onCheckedChange = { desired ->
                when (toggleAction(permission, granted, desired)) {
                    ToggleAction.REQUEST_RUNTIME -> onRequestRuntime(permission.runtimePermissions)
                    ToggleAction.OPEN_SYSTEM -> onOpenSystem()
                    ToggleAction.NONE -> Unit
                }
            },
        )
    }
}
