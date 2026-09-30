package com.aura.aura_ui.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Android skins AURA has to survive. Detection is deliberately coarse — what we
 * care about is the *behaviour family* (which vendor security-centre gates the
 * app), not the exact model.
 */
enum class Oem {
    XIAOMI, // MIUI / HyperOS — Xiaomi, Redmi, POCO
    OPPO, // ColorOS — OPPO, realme
    VIVO, // Funtouch / OriginOS — vivo, iQOO
    ONEPLUS, // OxygenOS (ColorOS-derived since OOS 12)
    HUAWEI, // EMUI / HarmonyOS
    HONOR, // MagicOS
    SAMSUNG, // One UI
    OTHER, // stock-ish Android (Pixel, Motorola, Nothing, …)
}

/**
 * OEM-specific compatibility knowledge, kept in one place.
 *
 * ## Why this exists
 * `Settings.canDrawOverlays()` is **not** authoritative on every Android skin.
 * MIUI/HyperOS ships a second, private gate — "Display pop-up windows while
 * running in background" (MIUI appop 10021) — that the public API cannot read
 * and an app cannot request. When it is denied, `WindowManager.addView()`
 * returns normally and the window is silently never composited: the classic
 * "AURA's bubble doesn't show up on my Redmi" report.
 *
 * The same vendors also gate background service starts behind an "Autostart"
 * toggle, which is what kills the wake-word service after a reboot.
 *
 * Neither can be granted programmatically. All we can do is (a) detect the
 * failure empirically — see `OverlayVisibilityProbe` — and (b) deep-link the
 * user to the right vendor screen with the right words.
 *
 * The decision layer ([detect], [hasHiddenOverlayGate], [overlayFixSteps]) is
 * pure so it is unit-testable on hardware we do not own. Only [openFirstAvailable]
 * touches the framework.
 */
object OemCompat {

    private const val TAG = "OemCompat"

    /** The running device's OEM family. Cheap; [Build] fields are constants. */
    val current: Oem by lazy { detect(Build.MANUFACTURER, Build.BRAND) }

    /**
     * Map manufacturer/brand strings onto a behaviour family.
     *
     * Both fields are consulted because Redmi and POCO devices routinely report
     * a useless `MANUFACTURER` while `BRAND` carries the real identity.
     */
    fun detect(manufacturer: String?, brand: String?): Oem {
        val id = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
        return when {
            id.contains("xiaomi") || id.contains("redmi") || id.contains("poco") -> Oem.XIAOMI
            // OnePlus before OPPO: OxygenOS devices can report "oppo" in some fields.
            id.contains("oneplus") -> Oem.ONEPLUS
            id.contains("oppo") || id.contains("realme") -> Oem.OPPO
            id.contains("vivo") || id.contains("iqoo") -> Oem.VIVO
            id.contains("honor") -> Oem.HONOR
            id.contains("huawei") -> Oem.HUAWEI
            id.contains("samsung") -> Oem.SAMSUNG
            else -> Oem.OTHER
        }
    }

    /**
     * True when the OEM enforces an overlay gate *beyond* `SYSTEM_ALERT_WINDOW`,
     * i.e. `canDrawOverlays() == true` is not sufficient for a window to render.
     */
    fun hasHiddenOverlayGate(oem: Oem): Boolean = when (oem) {
        Oem.XIAOMI, Oem.OPPO, Oem.VIVO, Oem.HUAWEI, Oem.HONOR -> true
        Oem.ONEPLUS, Oem.SAMSUNG, Oem.OTHER -> false
    }

    /**
     * Literal, follow-along steps for the vendor's own settings wording.
     * `null` when the OEM needs nothing beyond the standard Android toggle.
     */
    fun overlayFixSteps(oem: Oem): List<String>? = when (oem) {
        Oem.XIAOMI -> listOf(
            "Open Settings → Apps → Manage apps → AURA",
            "Tap \"Other permissions\"",
            "Turn ON \"Display pop-up windows while running in background\"",
            "Also turn ON \"Display pop-up windows\" if it is listed",
            "Back on the app page, turn ON \"Autostart\"",
        )
        Oem.OPPO -> listOf(
            "Open Settings → Apps → AURA → Allow floating windows",
            "Turn ON \"Allow floating windows\" and \"Display over other apps\"",
            "Open Settings → Battery → App battery usage → AURA → Allow background activity",
            "In the Phone Manager app, enable \"Auto-launch\" for AURA",
        )
        Oem.VIVO -> listOf(
            "Open Settings → Apps → AURA → Permissions → Floating windows → Allow",
            "Open i Manager → App manager → Autostart manager → turn ON AURA",
            "Open Settings → Battery → High background power consumption → allow AURA",
        )
        Oem.HUAWEI, Oem.HONOR -> listOf(
            "Open Settings → Apps → AURA → Permissions → turn ON \"Display over other apps\"",
            "Open Settings → Battery → App launch → AURA → switch to \"Manage manually\"",
            "Enable all three: Auto-launch, Secondary launch, Run in background",
        )
        Oem.ONEPLUS, Oem.SAMSUNG, Oem.OTHER -> null
    }

    /**
     * Candidate `package/class` pairs for the vendor screen that owns the hidden
     * overlay/floating-window toggle, most specific first. Vendor components are
     * renamed between OS versions, so these are *candidates* — [openFirstAvailable]
     * tries each and falls back to the AOSP overlay screen.
     */
    fun overlaySettingsComponents(oem: Oem): List<Pair<String, String>> = when (oem) {
        Oem.XIAOMI -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.permissions.PermissionsEditorActivity",
            "com.miui.securitycenter" to "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
        )
        Oem.OPPO -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity",
            "com.color.safecenter" to "com.color.safecenter.permission.floatwindow.FloatWindowListActivity",
        )
        Oem.VIVO -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity",
            "com.iqoo.secure" to "com.iqoo.secure.safeguard.SoftPermissionDetailActivity",
        )
        Oem.HUAWEI, Oem.HONOR -> listOf(
            "com.huawei.systemmanager" to "com.huawei.permissionmanager.ui.MainActivity",
        )
        Oem.ONEPLUS, Oem.SAMSUNG, Oem.OTHER -> emptyList()
    }

    /**
     * Candidate components for the vendor "Autostart" / background-launch screen —
     * the toggle that decides whether AURA survives a reboot or an idle period.
     */
    fun autoStartComponents(oem: Oem): List<Pair<String, String>> = when (oem) {
        Oem.XIAOMI -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        Oem.OPPO -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        )
        Oem.VIVO -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        )
        Oem.ONEPLUS -> listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        Oem.HUAWEI, Oem.HONOR -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        Oem.SAMSUNG -> listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        Oem.OTHER -> emptyList()
    }

    /**
     * Try each candidate component in order and launch the first one that this
     * device actually resolves, falling back to [fallback] (typically the AOSP
     * "Display over other apps" screen) when none exist.
     *
     * @return true if some screen was launched.
     */
    fun openFirstAvailable(
        context: Context,
        candidates: List<Pair<String, String>>,
        fallback: Intent?,
    ): Boolean {
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                // MIUI's permission editor keys off this extra; harmless elsewhere.
                putExtra("extra_pkgname", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // resolveActivity is the cheap existence check; the try/catch covers
            // components that resolve but refuse a non-system caller.
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                Log.i(TAG, "Opened OEM settings screen $pkg/$cls")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "OEM screen $pkg/$cls refused: ${e.message}")
            }
        }
        return fallback?.let {
            try {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Fallback settings screen failed: ${e.message}")
                false
            }
        } ?: false
    }

    /** The AOSP "Display over other apps" screen for this app. */
    fun aospOverlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Open whichever screen owns the *hidden* overlay gate on this device,
     * falling back to the standard Android one.
     */
    fun openOverlaySettings(context: Context): Boolean = openFirstAvailable(
        context,
        overlaySettingsComponents(current),
        aospOverlaySettingsIntent(context),
    )

    /** Open the vendor autostart screen; no AOSP equivalent exists, so no fallback. */
    fun openAutoStartSettings(context: Context): Boolean = openFirstAvailable(
        context,
        autoStartComponents(current),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
}
