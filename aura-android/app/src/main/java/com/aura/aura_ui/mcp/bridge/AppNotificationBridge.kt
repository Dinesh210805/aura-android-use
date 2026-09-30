package com.aura.aura_ui.mcp.bridge

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.aura.aura_ui.notifications.AuraNotificationListenerService
import com.aura.mcp.bridge.NotificationActionSpec
import com.aura.mcp.bridge.NotificationBridge
import com.aura.mcp.bridge.NotificationOpResult
import com.aura.mcp.bridge.NotificationSnapshot
import com.aura.mcp.bridge.SpecialAccessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:app` binding for [NotificationBridge], backed by
 * [AuraNotificationListenerService]. Pure mapping — filtering/policy runs
 * server-side in the tool layer.
 *
 * Group-summary notifications are skipped: they duplicate their children
 * ("5 new messages") and their actions target the whole group.
 */
class AppNotificationBridge(context: Context) : NotificationBridge {

    private val appContext = context.applicationContext

    override fun accessState(): SpecialAccessState =
        if (AuraNotificationListenerService.isAccessGranted(appContext) &&
            AuraNotificationListenerService.current != null
        ) {
            SpecialAccessState.ENABLED
        } else {
            SpecialAccessState.DISABLED
        }

    override suspend fun activeNotifications(): List<NotificationSnapshot> =
        withContext(Dispatchers.Default) {
            val service = AuraNotificationListenerService.current ?: return@withContext emptyList()
            service.snapshots()
                .filterNot { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
                .map { it.toSnapshot() }
        }

    override suspend fun fireAction(
        key: String,
        actionTitle: String,
        replyText: String?,
    ): NotificationOpResult = withContext(Dispatchers.Default) {
        val service = AuraNotificationListenerService.current
            ?: return@withContext NotificationOpResult(false, "notification listener not connected")
        val error = service.fireAction(key, actionTitle, replyText)
        NotificationOpResult(success = error == null, error = error)
    }

    override suspend fun dismiss(key: String): NotificationOpResult =
        withContext(Dispatchers.Default) {
            val service = AuraNotificationListenerService.current
                ?: return@withContext NotificationOpResult(false, "notification listener not connected")
            val error = service.dismiss(key)
            NotificationOpResult(success = error == null, error = error)
        }

    private fun StatusBarNotification.toSnapshot(): NotificationSnapshot {
        val extras = notification.extras
        return NotificationSnapshot(
            key = key,
            packageName = packageName,
            appName = appLabel(packageName),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAtMs = postTime,
            isOngoing = isOngoing,
            isClearable = isClearable,
            category = notification.category,
            actions = notification.actions.orEmpty().mapNotNull { action ->
                val title = action.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NotificationActionSpec(
                    title = title,
                    supportsReply = !action.remoteInputs.isNullOrEmpty(),
                )
            },
        )
    }

    private fun appLabel(packageName: String): String = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
