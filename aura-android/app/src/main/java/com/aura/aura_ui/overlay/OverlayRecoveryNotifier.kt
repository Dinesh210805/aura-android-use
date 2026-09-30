package com.aura.aura_ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aura.aura_ui.R
import com.aura.aura_ui.compat.OemCompat

/**
 * Tells the user — in their phone's own words — why the assistant bubble never
 * appeared, and takes them straight to the screen that fixes it.
 *
 * ## Why a notification and not a dialog
 * The failure is detected inside a background service, seconds after the user
 * triggered AURA from somewhere else (wake word, quick tile, another app).
 * Android 10+ blocks background activity starts, so a notification is the only
 * channel that reliably reaches the user. It is also the right UX: the user is
 * mid-task, and a full-screen takeover would be worse than a tappable prompt.
 */
object OverlayRecoveryNotifier {

    private const val TAG = "OverlayRecovery"
    private const val CHANNEL_ID = "aura_overlay_recovery"
    private const val NOTIFICATION_ID = 1099

    /**
     * Post a recovery prompt for [verdict]. Repeat calls replace the existing
     * notification rather than stacking — the user only ever needs one.
     */
    fun notify(context: Context, verdict: OverlayVerdict) {
        if (verdict.isSuccess) return
        ensureChannel(context)

        val steps = OemCompat.overlayFixSteps(OemCompat.current)
        val body = buildString {
            append(verdict.userMessage)
            if (verdict == OverlayVerdict.OEM_SUPPRESSED && steps != null) {
                append("\n\n")
                append(steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))
            }
        }

        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, OverlayFixActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AURA can't show its bubble")
            .setContentText(verdict.userMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(tapIntent)
            .addAction(0, "Fix it", tapIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied (Android 13+). Nothing else we can do
            // from here — the in-app Permissions Health page still surfaces it.
            Log.w(TAG, "Cannot post overlay recovery notification: ${e.message}")
        }
    }

    /** Clear the prompt once the overlay is confirmed working again. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Setup problems",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Tells you when AURA is blocked by a system permission."
            },
        )
    }
}
