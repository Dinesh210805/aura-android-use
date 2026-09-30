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
    private const val NOTIFICATION_ID = 9_001

    /** Mono's only accent (`ui/theme/Mono.kt`) — semantic attention, never decoration. */
    private const val MONO_BLOOD = 0xFFA31621.toInt()

    /** Promotion to a status-bar chip only exists on API 36+. */
    private val supportsPromotedChip: Boolean get() = Build.VERSION.SDK_INT >= 36

    private val activeChannelId: String
        get() = if (supportsPromotedChip) CHANNEL_ID else CHANNEL_ID_QUIET

    /**
     * Post or update the status-bar chip with a short verb (e.g. "Ready", "Thinking").
     *
     * NOTE: this path uses `nm.notify()` and so will NOT be promoted to the status-bar chip
     * because Android 16 requires the FOREGROUND_SERVICE flag on the notification, which
     * only `startForeground()` sets. Use [buildNotification] from inside a foreground
     * service instead and post it via `startForeground(id, notification, type)`.
     */
    fun show(context: Context, status: AuraStatus = AuraStatus.Idle) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        nm.notify(NOTIFICATION_ID, buildNotification(context, status))
        Log.d(TAG, "Live update notification posted (shade-only): ${status.verb}")
    }

    /** Update the chip mid-execution. */
    fun update(context: Context, status: AuraStatus) = show(context, status)

    /** Back-compat string overload used by old call sites. Treated as the [AuraStatus.Idle] verb. */
    @Deprecated("Pass AuraStatus", ReplaceWith("show(context, AuraStatus.Idle)"))
    fun show(context: Context, taskDescription: String) =
        show(context, AuraStatus.Idle)

    /** Cancel the chip. */
    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        Log.d(TAG, "Live update notification cancelled")
    }

    /**
     * Build a chip-style notification suitable for posting via `startForeground()`.
     * The caller is responsible for calling [ensureChannelCreated] once before first use.
     */
    fun buildNotification(context: Context, status: AuraStatus): Notification {
        ensureChannel(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        return if (Build.VERSION.SDK_INT >= 36) {
            buildPromotedNotification(context, status.verb)
        } else {
            buildCompatNotification(context, status.verb)
        }
    }

    /** Back-compat string overload — old call sites passing a raw verb string. */
    @Deprecated("Pass AuraStatus", ReplaceWith("buildNotification(context, AuraStatus.Idle)"))
    fun buildNotification(context: Context, verb: String): Notification {
        ensureChannel(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        return if (Build.VERSION.SDK_INT >= 36) {
            buildPromotedNotification(context, verb)
        } else {
            buildCompatNotification(context, verb)
        }
    }

    /** Public channel-id accessor for callers that need to reference the channel directly. */
    val channelId: String get() = activeChannelId

    /**
     * Invisible IMPORTANCE_MIN placeholder — keeps the foreground service alive
     * with NO pill/chip/card. Used when [ChipVisibilityPolicy] decides the
     * status surface should not render (app backgrounded + idle, or the
     * overlay's automation pill owns the notch).
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

        // Mirror AuraOverlayService.promoteIfSupported (lines 974-1003) byte-for-byte.
        // Key choices known to produce PROMOTED_ONGOING flag on OxygenOS / Android 16:
        //   - color = white (0xFFFFFFFF), NOT brand purple
        //   - NO setColorized() — system rejects the chip if colorized is requested
        //     without an FGS attachment at build time
        //   - FOREGROUND_SERVICE_DEFAULT behavior
        //   - At least one Action
        // BROKEN — see REVIEW_LOG OV3. This is an *implicit* service intent (action
        // string + setPackage, no component). Nothing in AndroidManifest.xml declares
        // an intent-filter for "com.aura.aura_ui.STOP_OVERLAY", so it resolves to no
        // service and tapping "Hide" does nothing. It has never worked; the handler it
        // aimed at (AssistantForegroundService.handleStopOverlay) drove the legacy
        // FloatingMicOverlay stack and was itself unreachable — deleted 2026-08-16.
        //
        // The working idiom in this codebase is an EXPLICIT intent, as used by the
        // sibling chip: Intent(this, AuraOverlayService::class.java).apply { action = … }
        // (AuraOverlayService:1696, :1883). Fixing this means picking the real hide
        // target — AuraOverlayService.hide(context) at :269 — which is a behaviour
        // change, so it is recorded rather than done here.
        val hidePi = PendingIntent.getService(
            context, 10,
            android.content.Intent("com.aura.aura_ui.STOP_OVERLAY").apply {
                setPackage(context.packageName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hideAction = Notification.Action.Builder(null, "Hide", hidePi).build()
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
            .addAction(hideAction)
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
