package com.aura.aura_ui.agent.conversation

/**
 * What the brain lane decided to DO, separate from what AURA should SAY about it.
 *
 * Kept apart on purpose: every action still needs a spoken line, and a model that had to encode
 * both into one string would inevitably leak protocol words ("PHONE:") into speech.
 */
sealed interface BrainLaneAction {
    /** Nothing to do beyond speaking — an ordinary conversational answer. */
    data object None : BrainLaneAction

    /** Start a phone task. [task] is the goal, already rewritten with conversational context. */
    data class Phone(val task: String) : BrainLaneAction

    /**
     * Pick up the task the user abandoned earlier, with its plan, facts and dead ends intact.
     *
     * Deliberately distinct from [Resume]: "carry on with what you were doing" (un-pause a live
     * run) and "go back to what we never finished" (restart an interrupted one from its ledger)
     * are different operations on different runs, and a model asked to express both with one
     * word would pick wrong exactly when it matters.
     */
    data object Continue : BrainLaneAction

    /**
     * Redirect or amend the task already running. [newGoal] is non-null only when the user
     * changed WHAT they want, as opposed to adding a constraint to the existing goal.
     */
    data class Steer(val directive: String, val newGoal: String? = null) : BrainLaneAction

    /** Stop the running task now. */
    data object Abort : BrainLaneAction

    /** Hold the running task where it is. */
    data object Pause : BrainLaneAction

    /** Let a held task carry on. */
    data object Resume : BrainLaneAction
}

/** A durable fact the lane decided to store. [type] is raw; the caller maps and filters it. */
data class MemoryWrite(val type: String, val text: String)

/** A reminder the lane decided to set. */
data class ReminderWrite(val whenEpochMs: Long, val text: String)

/** One parsed brain-lane reply: what to say, what to do, and what to persist. */
data class BrainLaneReply(
    val spoken: String,
    val action: BrainLaneAction = BrainLaneAction.None,
    val memories: List<MemoryWrite> = emptyList(),
    val reminders: List<ReminderWrite> = emptyList(),
)

/**
 * Parses the brain lane's line-prefixed reply. Pure — no Android, no network, no clock.
 *
 * ### Why line prefixes rather than JSON
 *
 * The lane runs on whatever model the user configured, including small local ones. Strict JSON
 * is the format those fail at first, and a parse failure here costs a whole conversational turn.
 * Line prefixes degrade gracefully: an unrecognised reply is still perfectly good speech, so the
 * worst case is "AURA answered instead of acting", never "AURA said nothing".
 *
 * That principle drives every rule below — **the parser never throws and never returns nothing.**
 * A malformed `REMIND` drops that one reminder rather than the reply; an unparseable line becomes
 * part of what AURA says.
 */
object BrainLaneParser {

    private const val ANSWER = "ANSWER:"
    private const val PHONE = "PHONE:"
    private const val CONTINUE = "CONTINUE:"
    private const val STEER = "STEER:"
    private const val GOAL = "GOAL:"
    private const val ABORT = "ABORT:"
    private const val PAUSE = "PAUSE:"
    private const val RESUME = "RESUME:"
    private const val MEMORY = "MEMORY:"
    private const val REMIND = "REMIND:"

    /** Separates the two fields of MEMORY / REMIND payloads. */
    private const val FIELD = '|'

    fun parse(raw: String?): BrainLaneReply {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return BrainLaneReply(spoken = "")

        val spoken = StringBuilder()
        val memories = mutableListOf<MemoryWrite>()
        val reminders = mutableListOf<ReminderWrite>()
        // First recognised action wins. A reply carrying both PHONE and STEER is self-
        // contradictory (start something new / change something running); obeying the later one
        // would make behaviour depend on the model's line order, which is not a decision anyone
        // made. Taking the first is at least stable and explainable.
        var action: BrainLaneAction = BrainLaneAction.None
        var pendingGoal: String? = null

        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            when {
                t.startsWith(ANSWER, true) -> spoken.appendLine(t.after(ANSWER))

                t.startsWith(PHONE, true) ->
                    if (action is BrainLaneAction.None) {
                        t.after(PHONE).takeIf { it.isNotEmpty() }
                            ?.let { action = BrainLaneAction.Phone(it) }
                    }

                // Checked before RESUME would ever be reached, but they are distinct prefixes so
                // order is not load-bearing — see the KDoc on BrainLaneAction.Continue.
                t.startsWith(CONTINUE, true) ->
                    if (action is BrainLaneAction.None) action = BrainLaneAction.Continue

                t.startsWith(STEER, true) ->
                    if (action is BrainLaneAction.None) {
                        t.after(STEER).takeIf { it.isNotEmpty() }
                            ?.let { action = BrainLaneAction.Steer(it) }
                    }

                // Optional companion to STEER — present only when the goal itself changed.
                t.startsWith(GOAL, true) -> pendingGoal = t.after(GOAL).takeIf { it.isNotEmpty() }

                t.startsWith(ABORT, true) -> if (action is BrainLaneAction.None) action = BrainLaneAction.Abort
                t.startsWith(PAUSE, true) -> if (action is BrainLaneAction.None) action = BrainLaneAction.Pause
                t.startsWith(RESUME, true) -> if (action is BrainLaneAction.None) action = BrainLaneAction.Resume

                t.startsWith(MEMORY, true) -> parseMemory(t.after(MEMORY))?.let(memories::add)
                t.startsWith(REMIND, true) -> parseReminder(t.after(REMIND))?.let(reminders::add)

                // No recognised prefix. Treat it as speech rather than dropping it: a model that
                // answered in plain prose has still answered, and silence would be the worse bug.
                else -> spoken.appendLine(t)
            }
        }

        val steer = action as? BrainLaneAction.Steer
        if (steer != null && pendingGoal != null) action = steer.copy(newGoal = pendingGoal)

        return BrainLaneReply(
            spoken = spoken.toString().trim(),
            action = action,
            memories = memories,
            reminders = reminders,
        )
    }

    /** `USER | prefers filter coffee` → [MemoryWrite]. A missing type is left to the caller. */
    private fun parseMemory(payload: String): MemoryWrite? {
        val idx = payload.indexOf(FIELD)
        val type = if (idx >= 0) payload.take(idx).trim() else ""
        val body = if (idx >= 0) payload.substring(idx + 1).trim() else payload.trim()
        return body.takeIf { it.isNotEmpty() }?.let { MemoryWrite(type, it) }
    }

    /**
     * `1765432100000 | call the dentist` → [ReminderWrite].
     *
     * A non-numeric time drops the reminder rather than guessing one. Inventing a time would set
     * a reminder that fires at the wrong moment, which is worse for the user than none at all —
     * they would believe it was set.
     */
    private fun parseReminder(payload: String): ReminderWrite? {
        val idx = payload.indexOf(FIELD)
        if (idx < 0) return null
        val whenMs = payload.take(idx).trim().toLongOrNull() ?: return null
        val body = payload.substring(idx + 1).trim()
        return body.takeIf { it.isNotEmpty() }?.let { ReminderWrite(whenMs, it) }
    }

    private fun String.after(prefix: String): String = substring(prefix.length).trim()
}
