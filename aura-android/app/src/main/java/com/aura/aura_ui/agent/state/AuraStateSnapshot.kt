package com.aura.aura_ui.agent.state

/**
 * One notification, reduced to the only two fields that leave the device: which app, and who it is
 * from. Message bodies are deliberately absent — the conversation plane sends this straight to
 * Google, and "Sarah messaged you" is enough to open a conversation intelligently while message
 * text never leaves the phone.
 */
data class NotificationBrief(val app: String, val sender: String? = null)

/** A phone task currently being driven by the agent. */
data class RunningTask(val label: String, val lastProgress: String? = null)

/**
 * What is happening on this phone right now.
 *
 * ### Why this exists
 *
 * The brain lane used to reason with no idea what was on screen, whether a task was already
 * running, or what the agent had just done — so it offered to start work already underway and
 * greeted the user identically whether or not anything had happened. This is the shared grounding
 * that both the agent and the conversation plane read.
 *
 * Every field is nullable and every one is optional: an unknown signal is simply omitted, never
 * guessed. A snapshot that knows nothing renders nothing, which tells the model "unknown" rather
 * than falsely asserting "nothing is happening".
 */
data class AuraStateSnapshot(
    val foregroundApp: String? = null,
    val screenName: String? = null,
    val browserPage: String? = null,
    val runningTask: RunningTask? = null,
    /** A task that was interrupted and could still be picked up. */
    val unfinishedTask: String? = null,
    val lastAgentAction: String? = null,
    val notifications: List<NotificationBrief> = emptyList(),
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val nowPlaying: String? = null,
    val dndOn: Boolean? = null,
) {

    /**
     * The block handed to the brain lane, or null when nothing is known.
     *
     * Order is deliberate and load-bearing: where the user is comes first because models read the
     * top of a block most reliably, and that is the fact most likely to change the right answer.
     */
    fun renderForBrainLane(): String? {
        val lines = buildList {
            foregroundApp?.let { app ->
                add(if (screenName != null) "They are in $app, on \"$screenName\"." else "They are in $app.")
            }
            browserPage?.let { add("A browser window is open on $it.") }
            runningTask?.let { task ->
                val progress = task.lastProgress?.let { " ($it)" }.orEmpty()
                add("A phone task is running right now: ${task.label}$progress.")
            }
            unfinishedTask?.let { add("An earlier task was left unfinished: $it.") }
            lastAgentAction?.let { add("The last thing the agent did: $it.") }
            if (notifications.isNotEmpty()) add("Unread: ${renderNotifications()}.")
            batteryPercent?.let { add("Battery $it%, ${if (isCharging) "charging" else "not charging"}.") }
            nowPlaying?.let { add("Playing: $it.") }
            if (dndOn == true) add("Do not disturb is on.")
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * The handful of things worth AURA raising unprompted when a conversation opens — most
     * important first.
     *
     * Empty is the common case and the point: a phone with nothing going on earns a plain hello,
     * not a status report. Reciting battery and connectivity every session is what made the old
     * greeting feel robotic.
     */
    fun notableFacts(): List<String> = buildList {
        unfinishedTask?.let { add("an unfinished task: $it") }
        if (notifications.isNotEmpty()) {
            add("${notifications.size} unread: ${renderNotifications()}")
        }
        batteryPercent?.let { if (it <= LOW_BATTERY_PERCENT && !isCharging) add("battery is at $it%") }
    }

    /** Groups senders under their app: `WhatsApp (Sarah, Mom), Gmail`. */
    private fun renderNotifications(): String =
        notifications.groupBy { it.app }.entries.joinToString(", ") { (app, items) ->
            val senders = items.mapNotNull { it.sender }.distinct()
            if (senders.isEmpty()) app else "$app (${senders.joinToString(", ")})"
        }

    private companion object {
        /** At or below this, and off the charger, it is worth mentioning unprompted. */
        const val LOW_BATTERY_PERCENT = 20
    }
}
