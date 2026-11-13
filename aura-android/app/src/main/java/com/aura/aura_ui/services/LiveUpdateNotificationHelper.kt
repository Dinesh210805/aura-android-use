package com.aura.aura_ui.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aura.aura_ui.MainActivity
import com.aura.aura_ui.R

/**
 * Manages Android 16 Live Update (Promoted Ongoing) status-bar chips for AURA.
 *
 * Two recipes:
 *  - **API 36+**: uses the platform [Notification.Builder] with [Notification.ProgressStyle]
 *    + [Notification.Builder.setShortCriticalText] + the `android.requestPromotedOngoing`
 *    extra. This is the exact recipe the AuraOverlayService uses for its known-working chip.
 *  - **API < 36**: falls back to a regular ongoing [NotificationCompat] notification that
 *    lives in the shade only.
 *
 * The channel must be IMPORTANCE_HIGH and notifications must be VISIBILITY_PUBLIC for the
 * system to consider promotion.
 */
object LiveUpdateNotificationHelper {

    private const val TAG = "LiveUpdateNotif"
    // v2: previous installs created `aura_live_update` with IMPORTANCE_LOW. Android 16
    // forbids deleting a channel that's in use by a foreground service, so we use a
    // distinct channel id to bypass the old one. The old channel is orphaned but
    // harmless — no notifications post to it.
    private const val CHANNEL_ID = "aura_live_update_v2"

    /**
     * Pre-Android-16 devices get their own channel at IMPORTANCE_LOW.
     *
     * IMPORTANCE_HIGH exists solely to make the notification *promotion-eligible*
     * on API 36+. Below that there is no promotion, so the same setting buys
     * nothing and costs a heads-up banner that covers the app every time the verb
     * changes — the "permanent Ready pill is noise" complaint ChipVisibilityPolicy
     * was written to fix, in a louder form (observed on a Redmi/Android 13).
     *
     * A separate id is required, not just a different importance argument: the
     * system ignores importance changes to an existing channel (only the user can
     * lower one), so installs that already created v2 at HIGH would keep it. Same
     * reason the v1 → v2 rename exists above.
     */
    private const val CHANNEL_ID_QUIET = "aura_live_update_quiet_v3"
    private const val CHANNEL_NAME = "AURA Status"

    /** Mono's only accent (`ui/theme/Mono.kt`) — semantic attention, never decoration. */
    private const val MONO_BLOOD = 0xFFA31621.toInt()

    /** Promotion to a status-bar chip only exists on API 36+. */
    private val supportsPromotedChip: Boolean get() = Build.VERSION.SDK_INT >= 36

    private val activeChannelId: String
        get() = if (supportsPromotedChip) CHANNEL_ID else CHANNEL_ID_QUIET

    /**
     * Build the working chip for `startForeground()`. Android 16 promotes only a notification
     * that carries the FOREGROUND_SERVICE flag, which `notify()` does not set.
     */
    fun buildNotification(context: Context, status: AuraStatus): Notification {
        ensureChannel(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        return if (supportsPromotedChip) {
            buildPromotedNotification(context, status.verb)
        } else {
            buildCompatNotification(context, status.verb)
        }
    }

    /**
     * IMPORTANCE_MIN placeholder that keeps the foreground service alive while the agent is idle:
     * no status-bar icon, collapsed at the bottom of the shade. Used whenever
     * [ChipVisibilityPolicy] says [ChipVisibilityPolicy.Mode.SILENT].
     */
    fun buildSilentNotification(context: Context): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SILENT_CHANNEL_ID,
                    "AURA background (silent)",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Invisible placeholder while AURA waits in the background"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                },
            )
        }
        return NotificationCompat.Builder(context, SILENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private const val SILENT_CHANNEL_ID = "aura_status_silent"

    /** Eagerly create the channel — call once at service startup before first build. */
    fun ensureChannelCreated(context: Context) {
        ensureChannel(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    }

    /**
     * Android 16+ recipe — mirrors AuraOverlayService.kt:945-1003 which is proven to
     * produce the Promoted Ongoing chip in the status bar.
     */
    @androidx.annotation.RequiresApi(36)
    private fun buildPromotedNotification(context: Context, verb: String): Notification {
        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // ProgressStyle is REQUIRED for promotion eligibility on Android 16+, but we
        // hide the bar (progress=0, not indeterminate) so the chip stays compact —
        // an indeterminate bar would expand the chip into a banner.
        val progressStyle = Notification.ProgressStyle().apply {
            setProgress(0)
            setStyledByProgress(false)
        }

        // Same recipe as AuraOverlayService.promoteIfSupported, which is known to promote on
        // OxygenOS / Android 16: white color, no setColorized(), FOREGROUND_SERVICE_DEFAULT.
        val openAction = Notification.Action.Builder(null, "Open", openPi).build()

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("AURA")
            .setContentText(verb)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFFFFFFFF.toInt())  // White, like the working AuraOverlayService chip
            .setContentIntent(openPi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFAULT)
            .setStyle(progressStyle)
            .setShortCriticalText(verb)
            .addAction(openAction)

        // Belt + suspenders: the framework recognises this extras key directly.
        builder.addExtras(Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        })

        val n = builder.build()
        Log.i(
            TAG,
            "Built promoted notification: hasPromotableCharacteristics=" +
                "${n.hasPromotableCharacteristics()} flags=0x${Integer.toHexString(n.flags)}",
        )
        return n
    }

    /** Pre-Android-16 fallback — plain ongoing notification in the shade. */
    private fun buildCompatNotification(context: Context, verb: String): Notification {
        return NotificationCompat.Builder(context, activeChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AURA")
            .setContentText(verb)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // No setColorized(): on an ongoing FGS notification it paints the whole
            // row in the accent, which produced a full-width purple slab over the
            // app. setColor alone just tints the icon and app name, which is what a
            // status row should do. Blood is the only accent in the Mono language —
            // 0xFF6B4EFF was a leftover from the pre-Mono palette.
            .setColor(MONO_BLOOD)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * IMPORTANCE_HIGH is required for the system to consider promotion. Lower
     * importance silently demotes the notification to shade-only.
     */
    private fun ensureChannel(nm: NotificationManager) {
        val id = activeChannelId
        if (nm.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(
            id,
            CHANNEL_NAME,
            // HIGH only where it buys promotion; elsewhere it is just a banner
            // over the user's screen. See CHANNEL_ID_QUIET.
            if (supportsPromotedChip) {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                NotificationManager.IMPORTANCE_LOW
            },
        ).apply {
            description = "AURA connection and task status (status-bar chip on Android 16+)"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)  // chip should be silent — no heads-up sound
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }
}
