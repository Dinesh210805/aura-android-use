package com.aura.aura_ui.presentation.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aura.aura_ui.compat.OemCompat

// ============================================================================
// PERMISSION SYSTEM LAUNCHER — opens the correct Android settings page for a
// SPECIAL-access permission (accessibility, overlay, notification-listener,
// exact-alarms, battery, screen-capture). RUNTIME permissions are handled by
// each screen's own ActivityResult launcher, not here.
//
// Shared by Settings and Permissions Health so the routing lives in one place.
// ============================================================================

/** Open the system page that grants [permission]. Screen-capture defers to [onScreenCapture]. */
fun openPermissionSystemPage(
    context: Context,
    permission: AuraPermission,
    onScreenCapture: () -> Unit,
) {
    val pkg = Uri.parse("package:${context.packageName}")

    // Overlay is the one permission where "granted" can still mean "not working".
    // MIUI/HyperOS, ColorOS, Funtouch and EMUI gate floating windows behind a
    // SECOND, private toggle that Settings.canDrawOverlays() cannot see. Once the
    // AOSP toggle is on, the only useful destination is the vendor screen that
    // owns the hidden one — the AOSP page would just show an already-on switch.
    if (permission.id == "overlay" &&
        Settings.canDrawOverlays(context) &&
        OemCompat.hasHiddenOverlayGate(OemCompat.current)
    ) {
        OemCompat.openOverlaySettings(context)
        return
    }

    val intent = when (permission.id) {
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notification_access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        // Opens Languages & input → On-screen keyboards, where the user enables AURA Keyboard.
        "aura_keyboard" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        "exact_alarms" ->
            if (Build.VERSION.SDK_INT >= 31) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            } else {
                null
            }
        "screen_capture" -> {
            onScreenCapture()
            null
        }
        // RUNTIME perms never reach here; fall back to app details if an unknown
        // special id appears.
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }
    intent?.let {
        try {
            context.startActivity(it)
        } catch (e: Exception) {
            android.util.Log.e("PermissionSystem", "Failed to open settings for ${permission.id}", e)
        }
    }
}
