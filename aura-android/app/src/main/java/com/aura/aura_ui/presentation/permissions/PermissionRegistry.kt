package com.aura.aura_ui.presentation.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aura.aura_ui.compat.OemCompat

// ============================================================================
// PERMISSION REGISTRY — single source of truth for every permission / special
// access AURA needs, split into two tiers (spec §4):
//
//   Tier 1 — requested during onboarding; nothing works without them.
//   Tier 2 — requested contextually, the first time a tool needs them.
//
// Consumed by: the onboarding wizard, the Permissions Health page, the Home
// health banner, and (later) tool-dispatch "needs permission X" errors.
// Closes the gap where 31 manifest permissions existed but only 2 were ever
// requested at runtime.
// ============================================================================

/** How a permission is granted: a runtime dialog or a system settings screen. */
enum class PermissionKind { RUNTIME, SPECIAL }

data class AuraPermission(
    val id: String,
    val title: String,
    /** Plain-language reason shown to the user BEFORE any system dialog. */
    val why: String,
    val tier: Int,
    val kind: PermissionKind,
    /** Manifest permission strings to request (RUNTIME kind only). */
    val runtimePermissions: List<String> = emptyList(),
    val isGranted: (Context) -> Boolean,
    /**
     * Device-specific extra steps, or null when the standard Android toggle is
     * enough. Non-null only on skins that add a permission the Android API
     * cannot see — where `isGranted` returning true is NOT proof it works.
     */
    val oemNote: ((Context) -> String?)? = null,
)

object PermissionRegistry {

    val all: List<AuraPermission> = listOf(
        // ── Tier 1 — core, requested at onboarding ─────────────────────────
        AuraPermission(
            id = "microphone",
            title = "Microphone",
            why = "Hear your voice commands and talk with you.",
            tier = 1,
            kind = PermissionKind.RUNTIME,
            runtimePermissions = listOf(Manifest.permission.RECORD_AUDIO),
            isGranted = { it.has(Manifest.permission.RECORD_AUDIO) },
        ),
        AuraPermission(
            id = "overlay",
            title = "Display over other apps",
            why = "Show the chat bubble on top of whatever you're doing.",
            tier = 1,
            kind = PermissionKind.SPECIAL,
            isGranted = { Settings.canDrawOverlays(it) },
            // On MIUI/HyperOS, ColorOS, Funtouch and EMUI this switch can read
            // "on" while a second, vendor-private permission still blocks the
            // window — the "AURA's bubble never appears on my Redmi" report.
            oemNote = { _ ->
                OemCompat.overlayFixSteps(OemCompat.current)?.let { steps ->
                    "Your phone needs one extra step:\n" +
                        steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
                }
            },
        ),
        AuraPermission(
            id = "accessibility",
            title = "Accessibility service",
            why = "See the screen and perform taps, swipes and typing for you. AURA reads on-screen content ONLY while running your tasks; banking and payment apps are always blocked.",
            tier = 1,
            kind = PermissionKind.SPECIAL,
            isGranted = { ctx ->
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                ).any {
                    it.resolveInfo.serviceInfo.packageName == ctx.packageName &&
                        it.resolveInfo.serviceInfo.name.endsWith("AuraAccessibilityService")
                }
            },
        ),
        AuraPermission(
            id = "notifications",
            title = "Notifications",
            why = "Keep you posted on task progress while AURA works in the background.",
            tier = 1,
            kind = PermissionKind.RUNTIME,
            runtimePermissions = if (Build.VERSION.SDK_INT >= 33) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            },
            isGranted = {
                Build.VERSION.SDK_INT < 33 || it.has(Manifest.permission.POST_NOTIFICATIONS)
            },
        ),

        // ── Tier 2 — contextual, granted the first time a tool needs them ──
        AuraPermission(
            id = "contacts",
            title = "Contacts",
            why = "Find a contact's number on-device when you say \"message Amma\". Numbers never leave your phone.",
            tier = 2,
            kind = PermissionKind.RUNTIME,
            runtimePermissions = listOf(Manifest.permission.READ_CONTACTS),
            isGranted = { it.has(Manifest.permission.READ_CONTACTS) },
        ),
        AuraPermission(
            id = "files",
            title = "Photos, media & files",
            why = "Search and open your files when you ask (\"open the PDF I downloaded\").",
            tier = 2,
            kind = PermissionKind.RUNTIME,
            runtimePermissions = if (Build.VERSION.SDK_INT >= 33) {
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                )
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            isGranted = { ctx ->
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.has(Manifest.permission.READ_MEDIA_IMAGES) ||
                        ctx.has(Manifest.permission.READ_MEDIA_VIDEO) ||
                        ctx.has(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    ctx.has(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            },
        ),
        AuraPermission(
            id = "notification_access",
            title = "Notification access",
            why = "Read and act on notifications (reply to messages, control media). Banking and security notifications are filtered out and never shown to the agent.",
            tier = 2,
            kind = PermissionKind.SPECIAL,
            isGranted = {
                NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName)
            },
        ),
        AuraPermission(
            id = "camera",
            title = "Camera",
            why = "Use the camera and flashlight when a task needs them.",
            tier = 2,
            kind = PermissionKind.RUNTIME,
            runtimePermissions = listOf(Manifest.permission.CAMERA),
            isGranted = { it.has(Manifest.permission.CAMERA) },
        ),
        AuraPermission(
            id = "location",
            title = "Location",
            why = "Answer \"where am I\" and share your location when you ask.",
            tier = 2,
            kind = PermissionKind.RUNTIME,
            runtimePermissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            isGranted = {
                it.has(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    it.has(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
        ),
        AuraPermission(
            id = "exact_alarms",
            title = "Alarms & reminders",
            why = "Set alarms and timers exactly when you ask for them.",
            tier = 2,
            kind = PermissionKind.SPECIAL,
            isGranted = { ctx ->
                if (Build.VERSION.SDK_INT >= 31) {
                    (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                } else {
                    true
                }
            },
        ),
        AuraPermission(
            id = "battery",
            title = "Unrestricted battery",
            why = "Keep long tasks running when the screen is off.",
            tier = 2,
            kind = PermissionKind.SPECIAL,
            isGranted = { ctx ->
                (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(ctx.packageName)
            },
        ),
        AuraPermission(
            id = "aura_keyboard",
            title = "AURA Keyboard",
            why = "Required so AURA can type in EVERY app. Many popular apps (ride, delivery, food, banking-adjacent and game apps like Rapido, Uber, Swiggy — built on React Native or Flutter) don't use standard Android text fields, so the normal typing path silently fails there. AURA's keyboard is the only way to enter text in those apps. It has no visible keyboard and never reads what you type — AURA switches to it ONLY to enter your text, then switches straight back to your usual keyboard.",
            tier = 1,
            kind = PermissionKind.SPECIAL,
            isGranted = { ctx ->
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val id = android.content.ComponentName(ctx, com.aura.aura_ui.ime.AuraKeyboardService::class.java)
                    .flattenToString()
                imm.enabledInputMethodList.any { it.id == id }
            },
        ),
        // NOTE (2026-08-12): the "Screen capture" entry was REMOVED, not disabled.
        //
        // It asked for MediaProjection, which perception no longer uses — screenshots now go
        // through AccessibilityService.takeScreenshot(), covered by the Accessibility permission
        // the user already grants in Tier 1. Leaving the entry would have asked users to approve
        // a capability nothing consumes, and (because the mirror is now gated off) it could
        // never report "granted" — a permanently-red row in Permissions Health with no fix.
        //
        // Re-add it if the screen-mirroring feature lands; that feature genuinely needs consent.
    )

    val tier1: List<AuraPermission> get() = all.filter { it.tier == 1 }
    val tier2: List<AuraPermission> get() = all.filter { it.tier == 2 }

    fun byId(id: String): AuraPermission? = all.firstOrNull { it.id == id }

    /** True when every Tier-1 permission is granted (the Home banner's condition). */
    fun coreHealthy(context: Context): Boolean = tier1.all { it.isGranted(context) }

    private fun Context.has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
