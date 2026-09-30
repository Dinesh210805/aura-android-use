package com.aura.aura_ui.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.aura.aura_ui.agent.state.AuraStateStore
import com.aura.aura_ui.agent.state.NotificationBrief

/**
 * AURA's notification listener — the agent's **event sense**.
 *
 * The system binds this service once the user grants "Notification access"
 * (Settings → Notifications → Device & app notifications → AURA). While bound
 * it can read the status bar, fire notification actions (including
 * `RemoteInput` direct-reply), and dismiss entries. The same grant also
 * unlocks `MediaSessionManager.getActiveSessions` for [com.aura.aura_ui.mcp.bridge.AppMediaBridge].
 *
 * The service itself holds NO logic beyond a static handle: the MCP bridge
 * ([com.aura.aura_ui.mcp.bridge.AppNotificationBridge]) reaches it through
 * [current] and does the mapping. All policy (sensitive-package filtering,
 * reply-text screening) lives server-side in the tool layer.
 */
class AuraNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        current = this
        Log.i(TAG, "Notification listener connected")
        feedState()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = feedState()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = feedState()

    /**
     * Publish what is waiting to [com.aura.aura_ui.agent.state.AuraStateStore], so AURA can open a
     * conversation with "Sarah's been trying to reach you" instead of a blank hello.
     *
     * **App and sender only — never the message body.** This state is sent to Google as part of the
     * Live prompt, so the contents of a message must not travel with it. Ongoing notifications
     * (music players, downloads, our own foreground-service notice) are dropped: they are not
     * things a person is waiting on.
     */
    private fun feedState() {
        runCatching {
            val briefs = snapshots()
                .asSequence()
                .filterNot { it.isOngoing }
                .filterNot { it.packageName == packageName }
                .map { sbn ->
                    NotificationBrief(
                        app = appLabel(sbn.packageName),
                        sender = sbn.notification.extras
                            ?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                            ?.takeIf { it.isNotBlank() },
                    )
                }
                .distinct()
                .toList()
            AuraStateStore.onNotifications(briefs)
        }.onFailure { Log.w(TAG, "notification state feed failed: ${it.message}") }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun onListenerDisconnected() {
        if (current === this) current = null
        Log.i(TAG, "Notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** Live status-bar snapshot; empty when the system returns null. */
    fun snapshots(): List<StatusBarNotification> =
        runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()

    /**
     * Fire the [Notification.Action] titled [actionTitle] on the notification
     * with [key]. When [replyText] is non-null the action's `RemoteInput` is
     * filled first (direct reply). Returns null on success, error text on failure.
     */
    fun fireAction(key: String, actionTitle: String, replyText: String?): String? {
        val sbn = snapshots().firstOrNull { it.key == key }
            ?: return "notification no longer active"
        val action = sbn.notification.actions.orEmpty().firstOrNull {
            it.title?.toString()?.trim()?.equals(actionTitle.trim(), ignoreCase = true) == true
        } ?: return "action '$actionTitle' not found"
        val pendingIntent = action.actionIntent ?: return "action has no intent"

        return runCatching {
            if (replyText != null) {
                val remoteInputs = action.remoteInputs
                    ?: return "action does not accept reply text"
                val fillIn = Intent()
                val results = Bundle()
                remoteInputs.forEach { results.putCharSequence(it.resultKey, replyText) }
                RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
                pendingIntent.send(this, 0, fillIn)
            } else {
                pendingIntent.send()
            }
            null
        }.getOrElse { t ->
            when (t) {
                is PendingIntent.CanceledException -> "action intent was cancelled by its app"
                else -> t.message ?: "failed to fire action"
            }
        }
    }

    /** Dismiss by key. Returns null on success, error text on failure. */
    fun dismiss(key: String): String? = runCatching {
        cancelNotification(key)
        null as String?
    }.getOrElse { it.message ?: "failed to dismiss" }

    companion object {
        private const val TAG = "AuraNotifListener"

        /** Set while the system has the listener bound; null otherwise. */
        @Volatile
        var current: AuraNotificationListenerService? = null
            private set

        /** Whether the user has granted Notification access to AURA. */
        fun isAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /** Component name — also the key MediaSessionManager needs. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context, AuraNotificationListenerService::class.java)
    }
}
