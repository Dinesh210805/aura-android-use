package com.aura.aura_ui.agent.conversation

/** How a [LiveCue] reaches the model. */
enum class CueDelivery {
    /** Sent as its own turn, so the model speaks straight away. */
    SPEAK_NOW,

    /** Added to the model's context without prompting a turn — it colours the reply the user has
     *  already triggered instead of talking over it. */
    CONTEXT_ONLY,
}

/**
 * A silent instruction the model renders as speech in its own words.
 *
 * ### Why cues instead of fixed sentences
 *
 * Canned strings are guaranteed to be right and guaranteed to sound like an answering machine by
 * the third time you hear them. A cue keeps the two decisions apart: code owns WHEN and WHETHER
 * (see [TaskNarrationGate]), the model owns the phrasing, so the same beat lands differently every
 * time while still landing exactly once.
 *
 * This is also the seam the voice `ask_user` tool will reuse: asking a question with options is
 * the same shape — a cue that carries content plus a rule for how to deliver it.
 */
data class LiveCue(val text: String, val delivery: CueDelivery)

/** Builders for the cues AURA sends today. Pure — no Android, no session, fully testable. */
object LiveCues {

    /** The moment a task is acknowledged. Speaks immediately so the user knows work has begun. */
    fun taskStarting(label: String): LiveCue = LiveCue(
        text = "[Say this now, in your own words, one short sentence: you are starting the task " +
            "\"$label\" on their phone. You do NOT have a result yet — do not say it is done, " +
            "finished, sent, ordered or found. Just that you are on it.]",
        delivery = CueDelivery.SPEAK_NOW,
    )

    /**
     * Fired the first time the user speaks while a task runs — the moment they would be wondering
     * whether talking interferes. Rides their reply rather than speaking separately, and the gate
     * guarantees they only ever hear it once per task.
     */
    fun taskOngoing(): LiveCue = LiveCue(
        text = "[Work this into your reply once, naturally: the phone task is still running in the " +
            "background, they can keep talking to you normally while it does, and they can tell you " +
            "to stop it or change it at any point. Say it once and never bring it up again.]",
        delivery = CueDelivery.CONTEXT_ONLY,
    )

    /** The task landed. Speaks immediately so the outcome is not held until a lull. */
    fun taskFinished(outcome: String): LiveCue = LiveCue(
        text = "[Say this now, in your own words: the phone task has finished. The outcome was: " +
            "$outcome. Report it plainly — if it failed, say so directly and briefly.]",
        delivery = CueDelivery.SPEAK_NOW,
    )

    /**
     * The conversation opener.
     *
     * With nothing notable this is a plain hello on purpose: reciting battery and connectivity
     * every session is what made the old greeting feel like a machine reading a status page.
     */
    fun opening(notable: List<String>): LiveCue = LiveCue(
        text = if (notable.isEmpty()) {
            "[Greet them now: one short, warm sentence, the way a friend answers the phone. " +
                "Nothing on their phone needs attention, so do not mention its state at all and do " +
                "not offer a list of things you can do.]"
        } else {
            "[Greet them now in one short sentence. Lead with the FIRST and most important item " +
                "below, mention a second only if it fits naturally, and never read the whole list. " +
                "Sound like a friend who noticed, not a system reporting status:\n" +
                notable.joinToString("\n") { "- $it" } + "]"
        },
        delivery = CueDelivery.SPEAK_NOW,
    )
}
