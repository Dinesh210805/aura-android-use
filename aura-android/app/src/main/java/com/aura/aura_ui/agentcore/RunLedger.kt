package com.aura.aura_ui.agentcore

/**
 * The plot of one run, kept outside the chat history and shown to the model as one short message
 * on long tasks: the request, the plan, the last actions and how far along it is.
 *
 * - Contract: thread-safe; one per turn ([TurnState.ledger]). [render] is bounded: the oldest
 *   action lines fold into a count, so it stays a few hundred tokens however long the run.
 * - Why: compaction summarises the middle of a long task; the ledger keeps the plan and the
 *   latest actions exact, so the model does not redo or lose steps.
 * - Change together: `ContextAssembler.LEDGER`, which marks the message so only the newest stays.
 */
class RunLedger {
    private var steps: List<String> = emptyList()
    private var done = 0
    private val actions = ArrayDeque<String>()
    private var folded = 0

    /** Plan steps finished so far; stuck detection counts a rise as progress. */
    @Volatile
    var planDone: Int = 0
        private set

    @Synchronized
    fun plan(steps: List<String>, done: Int) {
        this.steps = steps
        this.done = done
        planDone = done
    }

    /** One finished action, e.g. `tap {"som_id":12} -> ok`. */
    @Synchronized
    fun action(line: String) {
        actions.addLast(line.replace('\n', ' ').take(MAX_LINE))
        while (actions.size > MAX_ACTIONS) {
            actions.removeFirst()
            folded++
        }
    }

    /** The plan and the last actions, for the run digest; blank when nothing was recorded. */
    @Synchronized
    fun summary(): String = buildString {
        if (steps.isNotEmpty()) append("Plan: ").append(steps.mapIndexed { i, s -> (if (i < done) "[x] " else "[ ] ") + s }.joinToString("  ")).append('\n')
        actions.forEach { append("- ").append(it).append('\n') }
    }.trim()

    @Synchronized
    fun render(request: String, step: Int, minutes: Long, screens: String?): String = buildString {
        append(ContextAssembler.LEDGER).append(" Where this task stands (kept by the app, not by you).\n")
        append("Task: ").append(request.take(MAX_REQUEST)).append('\n')
        if (steps.isNotEmpty()) {
            append("Plan: ")
            steps.forEachIndexed { i, s -> append(if (i < done) "[x] " else "[ ] ").append(s).append("  ") }
            append('\n')
        }
        if (actions.isNotEmpty()) {
            append("Actions so far")
            if (folded > 0) append(" (").append(folded).append(" earlier not listed)")
            append(":\n")
            actions.forEach { append("- ").append(it).append('\n') }
        }
        screens?.let { append("Screens seen: ").append(it).append(" (screens, screen and screen_diff read them back)\n") }
        append("Step ").append(step).append(" · ").append(minutes).append(" min")
    }

    private companion object {
        const val MAX_ACTIONS = 12
        const val MAX_LINE = 140
        const val MAX_REQUEST = 300
    }
}
