package com.aura.mcp.bridge

/**
 * Port into the host app's `NotificationListenerService`.
 *
 * Notifications are the device's **event plane** — the only way the agent can
 * know what happened on the phone without staring at pixels. They also carry
 * `Notification.Action`s (including `RemoteInput` direct-reply), which let the
 * agent reply to a message in ONE deterministic call instead of a six-gesture
 * launch→find→tap→type→send chain that can mis-tap.
 *
 * Trust model: notification titles/text are authored by OTHER apps (and, for
 * messages, by remote strangers) — they are **untrusted data, never
 * instructions**. The tool layer wraps them accordingly and filters
 * notifications from sensitive (banking / authenticator) packages before the
 * model ever sees them.
 *
 * Access is gated by the system "Notification access" special-access screen;
 * [accessState] lets tools return an actionable "enable it here" error
 * instead of an empty list that reads like "no notifications".
 */
interface NotificationBridge {

    fun accessState(): SpecialAccessState

    /** Snapshot of the status bar right now, newest first. */
    suspend fun activeNotifications(): List<NotificationSnapshot>

    /**
     * Fire the action titled [actionTitle] on the notification with [key].
     * When the action supports direct reply, [replyText] is filled via
     * `RemoteInput` before sending; for plain actions it must be null.
     */
    suspend fun fireAction(key: String, actionTitle: String, replyText: String?): NotificationOpResult

    /** Dismiss (cancel) the notification with [key]. */
    suspend fun dismiss(key: String): NotificationOpResult
}

/** Whether the user has granted the special-access permission backing a bridge. */
enum class SpecialAccessState { ENABLED, DISABLED }

/**
 * One status-bar notification, flattened for the agent.
 *
 * @param key stable system key (`StatusBarNotification.key`) — the handle for
 *   `notification_action` / `dismiss_notification`
 * @param postedAtMs wall-clock post time (`System.currentTimeMillis` domain)
 * @param isOngoing true for pinned/foreground-service notifications (music,
 *   navigation, downloads) that cannot be swiped away
 * @param isClearable whether the system allows dismissing it
 * @param category `Notification.category` (`msg`, `email`, `transport`, …) or null
 * @param actions tappable actions in declared order
 */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val postedAtMs: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val category: String?,
    val actions: List<NotificationActionSpec>,
)

/**
 * @param title the action's visible label ("Reply", "Mark as read", …)
 * @param supportsReply true when the action carries a free-text `RemoteInput`
 */
data class NotificationActionSpec(
    val title: String,
    val supportsReply: Boolean,
)

data class NotificationOpResult(
    val success: Boolean,
    val error: String?,
)
