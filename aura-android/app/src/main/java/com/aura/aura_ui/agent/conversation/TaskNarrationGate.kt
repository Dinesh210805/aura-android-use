package com.aura.aura_ui.agent.conversation

/** The three moments in a task's life that AURA says something about. */
enum class TaskBeat {
    /** "Starting that now." Fires when the task is acknowledged. */
    STARTING,

    /** "Still going — talk to me normally, or tell me to stop." Fires the first time the user
     *  speaks while the task runs, which is exactly when they would be wondering. */
    ONGOING,

    /** The real outcome, once the task lands. */
    FINISHED,
}

/**
 * Guarantees each [TaskBeat] is narrated at most once per task.
 *
 * ### Why this is code and not a prompt rule
 *
 * The persona used to be told "mention this only once". Models do not track that across a long
 * session — the standing-by line came back every turn until it read as nagging. Counting is a job
 * for code; the model is only asked for the wording, and only when [claim] opens the gate.
 *
 * Pure and synchronous, so the whole rule is unit-testable. Access is synchronised because tool
 * dispatch runs on coroutines while the user's speech arrives on the socket thread.
 */
class TaskNarrationGate {

    private val fired = mutableSetOf<TaskBeat>()
    private var taskId: String? = null

    /** The task currently being narrated, or null when nothing is running. */
    val currentTaskId: String? @Synchronized get() = taskId

    /**
     * Arm the gate for a task. Re-arms every beat, but only for a genuinely new [taskId] — a
     * repeated start signal (a reconnect, a duplicate tracker callback) must not license a second
     * announcement of the same task.
     */
    @Synchronized
    fun onTaskStarted(taskId: String) {
        if (this.taskId == taskId) return
        this.taskId = taskId
        fired.clear()
    }

    /** Close the gate. Nothing can be narrated until another task starts. */
    @Synchronized
    fun onTaskFinished() {
        taskId = null
        fired.clear()
    }

    /**
     * True the first time this beat is asked for in the current task, false every time after — and
     * always false when no task is running, so a beat can never narrate a task that does not exist.
     */
    @Synchronized
    fun claim(beat: TaskBeat): Boolean {
        if (taskId == null) return false
        return fired.add(beat)
    }
}
