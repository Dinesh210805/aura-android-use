package com.aura.aura_ui.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aura.aura_ui.MainActivity
import com.aura.aura_ui.R
import com.aura.mcp.McpServerHealth

/**
 * Posts a dismissible alert when the MCP server crashes, and clears it when the
 * server recovers.
 *
 * Deliberately **separate from the activity chip**. The chip answers "what is AURA
 * doing right now"; this answers "something broke while you weren't looking". Folding
 * the second into the first is what produced the "Stuck" verb on a merely-booting
 * server, so they get separate channels and separate lifetimes.
 *
 * Thin by design: every decision lives in the pure, unit-tested [HealthAlertPolicy].
 * This class only knows how to talk to [NotificationManager].
 */
object McpHealthNotifier {

    private const val TAG = "McpHealthNotifier"
    private const val CHANNEL_ID = "aura_health"
    private const val NOTIFICATION_ID = 7101

    /**
     * Apply the policy's verdict for [health]. Safe to call on every emission.
     *
     * **Must never throw.** This is called from inside the foreground service's
     * status `combine {}` block, so an escaping exception would cancel that flow and
     * take the status chip down with it — a health-reporting feature killing the
     * status system would be a poor trade. `POST_NOTIFICATIONS` can be denied on
     * Android 13+, in which case `notify` throws `SecurityException`; the same
     * swallow-and-log pattern as `OverlayRecoveryNotifier` applies, and the in-app
     * MCP screen still surfaces health for anyone who denied it.
     */
    fun onHealth(context: Context, health: McpServerHealth) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        try {
            when (val decision = HealthAlertPolicy.decide(health)) {
                is HealthAlertPolicy.Decision.Alert -> {
                    ensureChannel(manager)
                    manager.notify(NOTIFICATION_ID, build(context, decision.reason))
                }
                HealthAlertPolicy.Decision.Clear -> manager.cancel(NOTIFICATION_ID)
                HealthAlertPolicy.Decision.Ignore -> Unit
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post MCP health notification: ${e.message}")
        } catch (e: RuntimeException) {
            // Defensive: an OEM NotificationManager throwing anything at all must not
            // be able to kill the chip.
            Log.w(TAG, "MCP health notification failed: ${e.message}")
        }
    }

    private fun build(context: Context, reason: String): Notification {
        // Tapping opens the app, where the MCP screen shows the full health detail
        // and the restart control.
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AURA server stopped")
            .setContentText(reason)
            // The reason can be long ("Port 8765 already in use by ..."), and a
            // truncated cause is a support ticket. Let it expand.
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setContentIntent(openApp)
            // Dismissible: this is an alert the user can acknowledge, not a status
            // that must persist. Auto-cancels on tap since tapping takes them to the
            // screen that explains it.
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AURA server health",
                // DEFAULT, not HIGH: worth noticing, not worth interrupting. Its own
                // channel means a user who finds it noisy can mute exactly this
                // without losing the status chip.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Tells you when the AURA MCP server stops working unexpectedly."
            },
        )
    }
}
