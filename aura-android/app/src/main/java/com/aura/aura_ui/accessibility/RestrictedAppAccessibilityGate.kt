package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aura.aura_ui.R
import com.aura.aura_ui.mcp.AppRestrictedApps
import com.aura.mcp.bridge.RestrictedAppEntry
import com.aura.mcp.bridge.RestrictedTier

/**
 * Phase 2 of the Restricted Apps feature: when a [RestrictedTier.DISABLE_ACCESSIBILITY]
 * app comes to the foreground, AURA turns its own accessibility service off —
 * the only way to stop banking/UPI apps' "please disable accessibility" check
 * from tripping — and tells the user what happened and how to undo it.
 *
 * ## Why this is a RACE, and how we try to win it
 *
 * GPay (and UPI apps generally) do not evaluate AURA at all: they call
 * `AccessibilityManager.getEnabledAccessibilityServiceList()`, drop system
 * services, and refuse if anything remains. A screen reader trips it exactly
 * like a trojan does. That check runs inside the app's own `onResume()`.
 *
 * So the ONLY variable we control is timing — whether the enabled list is
 * already empty by the moment they read it. The original implementation fired
 * on `TYPE_WINDOW_STATE_CHANGED`, which lands at roughly the same instant as
 * their check, and lost essentially every time.
 *
 * [onPackageSeen] therefore fires on the FIRST event of ANY type carrying a
 * restricted package (the service registers `typeAllMask`, so every event
 * already arrives — this is wiring, not new capability), and
 * [onNodeClicked] fires even earlier: when the user taps the app's icon on the
 * launcher, before the target process has even spawned. Earliest wins; all
 * paths converge on the same [pause].
 *
 * This is a probabilistic improvement, not a guarantee — a warm start leaves
 * far less headroom than a cold one. Verify on-device, per restricted app.
 *
 * ## Why there is no automatic way back
 *
 * `disableSelf()` is one-way: Android gives no API to re-enable a service
 * programmatically (letting an app silently re-enable its own accessibility
 * would defeat the exact security model this works around). Re-enabling
 * requires writing `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, which
 * needs `WRITE_SECURE_SETTINGS` — ungrantable via `pm grant` on ColorOS/OnePlus
 * (measured 2026-08-05), so it would mean a Shizuku dependency.
 *
 * The notification is therefore the way back. It is posted `ongoing` (not a
 * toast, not auto-dismissing) and deep-links straight to AURA's OWN
 * accessibility toggle where the OS supports that, because losing track of
 * "AURA is off" is the one real failure mode of this design.
 */
object RestrictedAppAccessibilityGate {

    private const val CHANNEL_ID = "aura_accessibility_disabled"
    private const val NOTIFICATION_ID = 8001
    private const val TAG = "RestrictedAppGate"

    /** `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` — API 31+, not in our compileSdk. */
    private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /**
     * Cheap de-dupe so the per-event hot path costs one reference compare for
     * the overwhelmingly common case (same package, many events).
     */
    @Volatile
    private var lastSeenPackage: String? = null

    /**
     * Call for EVERY event carrying a non-AURA package, regardless of event
     * type — whichever event type happens to arrive first for a restricted app
     * is the one that buys us the head start. No-op unless [packageName] is
     * registered at [RestrictedTier.DISABLE_ACCESSIBILITY] in
     * [AppRestrictedApps]; the softer [RestrictedTier.DECLINE_ONLY] tier is
     * enforced elsewhere, at the agent-invocation fast path, not here.
     */
    fun onPackageSeen(service: AccessibilityService, packageName: String) {
        if (packageName.isBlank() || packageName == lastSeenPackage) return
        lastSeenPackage = packageName

        val entry = AppRestrictedApps.get(service).entryFor(packageName)
        if (!shouldDisableFor(entry)) return
        val target = requireNotNull(entry) // shouldDisableFor already proved non-null

        Log.i(TAG, "Restricted app seen (${target.packageName}) — disabling accessibility")
        pause(service, target.appName)
    }

    /**
     * Earliest signal available: the user tapped something labelled like a
     * restricted app — typically its launcher icon, which happens BEFORE the
     * target process spawns. On a cold start that is the largest head start we
     * can get.
     *
     * Matching is deliberately strict (see [matchLabel]) because a loose match
     * would disable AURA whenever the words "Google Pay" appeared on any
     * screen.
     */
    fun onNodeClicked(service: AccessibilityService, label: String?) {
        val target = matchLabel(label, AppRestrictedApps.get(service).entries.value) ?: return
        Log.i(TAG, "Restricted app icon tapped (${target.appName}) — disabling accessibility early")
        pause(service, target.appName)
    }

    /**
     * Pure matcher, isolated for testability. Exact (case/whitespace-insensitive)
     * match on the app's display name, and only for the tier that warrants a
     * disable — a substring match would fire on ordinary prose.
     */
    internal fun matchLabel(
        label: String?,
        entries: Collection<RestrictedAppEntry>,
    ): RestrictedAppEntry? {
        val needle = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return entries.firstOrNull { entry ->
            shouldDisableFor(entry) && entry.appName.trim().lowercase() == needle
        }
    }

    /** Pure decision, isolated for testability: does [entry] warrant the auto-disable? */
    internal fun shouldDisableFor(entry: RestrictedAppEntry?): Boolean =
        entry?.tier == RestrictedTier.DISABLE_ACCESSIBILITY

    /**
     * Shared teardown for every trigger path. The notification is posted FIRST,
     * while the service context is still fully valid — [AccessibilityService.disableSelf]
     * immediately starts tearing this service down.
     */
    private fun pause(service: AccessibilityService, appName: String) {
        postReEnableNotification(service, appName)
        // Accessibility is disabled immediately below, but the overlay is a separate
        // foreground service. Tear it down first so a full-screen touchable window cannot
        // survive the accessibility handoff and freeze the restricted app underneath it.
        com.aura.aura_ui.overlay.AuraOverlayService.hideIfRunning()
        service.disableSelf()
    }

    /**
     * Whether the OS can deep-link to AURA's own accessibility entry rather
     * than the generic services list.
     * [Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS] landed in API 31.
     * Pure — unit-tested.
     */
    internal fun supportsDetailsDeepLink(sdkInt: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.S

    /**
     * Recovery intent. Prefers AURA's own toggle so re-enabling is one tap
     * instead of a hunt through every installed service; falls back to the
     * top-level list on older OS versions and on any OEM that does not honour
     * the details action.
     */
    private fun reEnableIntent(context: Context): Intent {
        val fallback = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (!supportsDetailsDeepLink(Build.VERSION.SDK_INT)) return fallback

        val component = ComponentName(context, AuraAccessibilityService::class.java)
        // Literal action rather than Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS:
        // the constant is not exposed in our compileSdk, but the action itself has
        // existed since API 31. resolveActivity below makes this safe — any OEM
        // that does not honour it falls back to the generic list.
        return Intent(ACTION_ACCESSIBILITY_DETAILS).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        }.takeIf { it.resolveActivity(context.packageManager) != null } ?: fallback
    }

    private fun postReEnableNotification(context: Context, appName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Accessibility auto-disabled",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Alerts you when AURA turns off its own accessibility for a restricted app"
                },
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            reEnableIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AURA's Accessibility turned off")
            .setContentText("$appName is restricted — tap to re-enable AURA when you're done.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$appName requires Accessibility to be off, so AURA turned itself off " +
                        "automatically. AURA's gestures and voice automation won't work again " +
                        "until you turn Accessibility back on — tap here when you're done.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
