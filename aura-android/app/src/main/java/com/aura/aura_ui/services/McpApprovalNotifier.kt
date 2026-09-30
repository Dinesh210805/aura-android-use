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
import com.aura.mcp.WebRtcApprovalRequest

/**
 * Posts a high-priority, action-carrying notification whenever a computer asks
 * to pair over WebRTC — so the request is answerable from wherever the user
 * actually is, not just from the MCP Center screen's [android.app.Dialog].
 *
 * The in-app `AlertDialog` in `McpCenterScreen` only composes while that screen
 * happens to be visible. A pairing attempt can land while the user is in a
 * different app, on the home screen, or with the screen off — this notifier is
 * what makes the request visible (and actionable) in all of those cases.
 *
 * Thin by design, same shape as [McpHealthNotifier]: this only knows how to talk
 * to [NotificationManager]. The actual approve/deny logic lives in
 * [WebRtcApprovalRequest]'s callbacks, invoked by
 * [com.aura.aura_ui.services.AssistantForegroundService] when the notification's
 * action buttons (or swipe-to-dismiss) fire `ACTION_MCP_APPROVE_CONNECTION` /
 * `ACTION_MCP_DENY_CONNECTION`.
 */
object McpApprovalNotifier {

    private const val TAG = "McpApprovalNotifier"
    private const val CHANNEL_ID = "aura_mcp_approval"
    private const val NOTIFICATION_ID = 7102

    /**
     * Show (or replace) the approval notification for [request].
     *
     * **Must never throw** — called from the foreground service's
     * `webRtcApprovalRequest` collector; `POST_NOTIFICATIONS` can be denied on
     * Android 13+, in which case `notify` throws `SecurityException`. The in-app
     * dialog still works for anyone who denied the permission, so this is a
     * best-effort addition, not the only path.
     */
    fun show(context: Context, request: WebRtcApprovalRequest) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        try {
            ensureChannel(manager)
            manager.notify(NOTIFICATION_ID, build(context, request))
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post MCP approval notification: ${e.message}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "MCP approval notification failed: ${e.message}")
        }
    }

    fun clear(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun build(context: Context, request: WebRtcApprovalRequest): Notification {
        val origin = listOfNotNull(
            request.host?.takeIf { it.isNotBlank() },
            request.platform?.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ")
        val code = request.verificationCode.chunked(3).joinToString(" ")
        val body = buildString {
            append("Code $code: approve only if your computer shows the same code. ")
            append("${request.clientName} v${request.version} wants to control this device.")
            if (origin.isNotBlank()) {
                append(" ($origin)")
            }
        }

        // Tapping the body opens the app to the MCP Center screen's own dialog,
        // in case the user wants to review Trusted Devices before deciding.
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val approveIntent = serviceAction(context, AssistantForegroundService.ACTION_MCP_APPROVE_CONNECTION)
        val denyIntent = serviceAction(context, AssistantForegroundService.ACTION_MCP_DENY_CONNECTION)

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Approve this computer? Code $code")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(0, "Approve", approveIntent).build())
            .addAction(Notification.Action.Builder(0, "Deny", denyIntent).build())
            // Swiping the notification away mirrors the dialog's onDismissRequest,
            // which also denies — silently ignoring an unanswered pairing request
            // would leave the WebRTC handshake hanging.
            .setDeleteIntent(denyIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
    }

    private fun serviceAction(context: Context, action: String): PendingIntent {
        val intent = Intent(context, AssistantForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AURA pairing requests",
                // HIGH, not DEFAULT: this is a security decision with a live
                // handshake waiting on it, worth a heads-up interruption — unlike
                // the health channel, which is just worth noticing.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Asks you to approve a computer that wants to control this device."
            },
        )
    }
}
