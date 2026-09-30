package com.aura.aura_ui.agent.state

import java.util.concurrent.atomic.AtomicReference

/**
 * The one place that knows what this phone is doing right now.
 *
 * ### Why a warm cache and not a fetch
 *
 * The conversation plane is a live voice loop where latency is the entire experience. Reading the
 * accessibility tree and the notification list at the moment the brain lane runs would add that
 * cost to every spoken reply. Instead the store is fed by events as they happen — a screen change,
 * a task starting, a notification arriving — so reading it is free and the worst case is being a
 * fraction of a second stale.
 *
 * ### Why a process singleton
 *
 * The writers are scattered by nature: the accessibility service, the agent's tool loop, the
 * notification listener, the browser bridge. The readers are the agent and the brain lane, which
 * are built independently in three different places (see `AuraAgent` construction sites). A single
 * owner would have to be threaded through all of them; a process-wide store is the honest shape for
 * a process-wide fact. [reset] exists so a fresh session never inherits the last one's screen.
 *
 * Updates are lock-free via compare-and-set: every setter touches exactly one field and leaves its
 * neighbours alone, because writers arrive on unrelated threads.
 */
object AuraStateStore {

    private val state = AtomicReference(AuraStateSnapshot())

    /** What is happening right now. Never null; an unknown phone is an empty snapshot. */
    fun current(): AuraStateSnapshot = state.get()

    /** Drop everything. Called when a conversation opens so stale context cannot leak into it. */
    fun reset() = state.set(AuraStateSnapshot())

    fun onForegroundApp(app: String?, screenName: String?) =
        update { it.copy(foregroundApp = app, screenName = screenName) }

    fun onBrowserPage(page: String?) = update { it.copy(browserPage = page) }

    fun onTaskStarted(label: String) =
        update { it.copy(runningTask = RunningTask(label), unfinishedTask = null) }

    /**
     * A progress line from a running task. Ignored when nothing is running — a line arriving after
     * the task ended would otherwise resurrect it, and AURA would report work that is not happening.
     */
    fun onTaskProgress(text: String) = update { s ->
        s.runningTask?.let { s.copy(runningTask = it.copy(lastProgress = text)) } ?: s
    }

    fun onTaskFinished() = update { it.copy(runningTask = null) }

    /** A task that was interrupted and could still be picked up. */
    fun onUnfinishedTask(label: String?) = update { it.copy(unfinishedTask = label) }

    fun onAgentAction(description: String) = update { it.copy(lastAgentAction = description) }

    fun onNotifications(items: List<NotificationBrief>) = update { it.copy(notifications = items) }

    fun onDeviceSignals(
        batteryPercent: Int?,
        isCharging: Boolean,
        nowPlaying: String?,
        dndOn: Boolean?,
    ) = update {
        it.copy(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            nowPlaying = nowPlaying,
            dndOn = dndOn,
        )
    }

    private inline fun update(crossinline change: (AuraStateSnapshot) -> AuraStateSnapshot) {
        while (true) {
            val old = state.get()
            if (state.compareAndSet(old, change(old))) return
        }
    }
}
