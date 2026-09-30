package com.aura.aura_ui.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Security
import androidx.compose.ui.graphics.vector.ImageVector

// ============================================================================
// PERMISSION ICONS — single mapping of AuraPermission.id -> icon, so every
// surface that lists permissions (Settings, Permissions Health, onboarding)
// uses the SAME glyph. Keeps PermissionRegistry UI-agnostic.
// ============================================================================

fun permissionIcon(id: String): ImageVector = when (id) {
    "microphone" -> Icons.Outlined.Mic
    "overlay" -> Icons.Outlined.Layers
    "accessibility" -> Icons.Outlined.Accessibility
    "notifications" -> Icons.Outlined.Notifications
    "contacts" -> Icons.Outlined.Contacts
    "files" -> Icons.Outlined.Folder
    "notification_access" -> Icons.Outlined.NotificationsActive
    "camera" -> Icons.Outlined.CameraAlt
    "location" -> Icons.Outlined.LocationOn
    "exact_alarms" -> Icons.Outlined.Alarm
    "battery" -> Icons.Outlined.BatteryChargingFull
    "screen_capture" -> Icons.Outlined.Screenshot
    "aura_keyboard" -> Icons.Outlined.Keyboard
    else -> Icons.Outlined.Security
}
