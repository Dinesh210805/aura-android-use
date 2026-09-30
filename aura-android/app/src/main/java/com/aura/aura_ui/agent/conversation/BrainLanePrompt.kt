package com.aura.aura_ui.agent.conversation

/**
 * Builds the brain lane's prompt. Pure — no Android, no clock, no network — so the whole
 * routing contract is unit-testable without a device or a provider key.
 *
 * ### The one rule that shapes this file
 *
 * The lane produces **content, not character**. AURA's voice, warmth and language-mirroring all
 * live in [Persona], which the Live model already has. If this prompt also asked for personality
 * we would have two persona definitions drifting apart, and a rendering layer with licence to
 * embellish — which is how "step 3 failed" becomes "we're mostly there". So: plain, factual,
 * short. The voice layer makes it sound like AURA.
 */
object BrainLanePrompt {

    /**
     * @param request what the user just said
     * @param recentTranscript the last few conversational turns, or null on the first turn
     * @param memoryContext durable memory the assistant already carries ([MemoryService.buildSessionContext])
     * @param recalled memories matched against this specific request
     * @param runningTask a rendered status of the phone task in flight, or null when none
     * @param pendingResume description of an interrupted task that could be picked up, or null
     * @param phoneState what is on the phone right now
     *   ([com.aura.aura_ui.agent.state.AuraStateSnapshot.renderForBrainLane]), or null when unknown
     * @param nowEpochMs current time, so the model can compute absolute reminder times
     */
    fun build(
        request: String,
        recentTranscript: String? = null,
        memoryContext: String? = null,
        recalled: List<String> = emptyList(),
        runningTask: String? = null,
        pendingResume: String? = null,
        phoneState: String? = null,
        nowEpochMs: Long,
    ): String = buildString {
        appendLine(ROLE)
        appendLine()
        appendLine(FORMAT)
        appendLine()
        appendLine(if (runningTask != null) RULES_TASK_RUNNING else RULES_IDLE)
        appendLine()
        appendLine("Current time (epoch ms): $nowEpochMs")

        if (!memoryContext.isNullOrBlank()) {
            appendLine()
            appendLine("# What you already know about this user")
            appendLine(memoryContext.trim())
        }
        if (recalled.isNotEmpty()) {
            appendLine()
            appendLine("# Possibly relevant to what they just said")
            recalled.forEach { appendLine("• ${it.trim()}") }
        }
        if (runningTask != null) {
            appendLine()
            appendLine("# The phone task running RIGHT NOW")
            appendLine(runningTask.trim())
        }
        if (!pendingResume.isNullOrBlank() && runningTask == null) {
            appendLine()
            appendLine("# An earlier task was left unfinished")
            appendLine(pendingResume.trim())
            appendLine(
                "If the user wants it picked up, reply CONTINUE: — that resumes it with its " +
                    "plan and findings intact. Starting it again with PHONE would throw those away.",
            )
        }
        // Before the transcript on purpose: what is on screen NOW decides more answers than what
        // was said several turns ago, and the model reads earlier blocks more reliably.
        if (!phoneState.isNullOrBlank()) {
            appendLine()
            appendLine("# What is on their phone right now")
            appendLine(phoneState.trim())
            appendLine(
                "Use this instead of asking. If they say \"send her a message\" and a chat is open, " +
                    "that is who they mean. Never state any of this back as a status report unless " +
                    "it actually answers what they asked.",
            )
        }
        if (!recentTranscript.isNullOrBlank()) {
            appendLine()
            appendLine("# Recent conversation")
            appendLine(recentTranscript.trim())
        }

        appendLine()
        appendLine("# What the user just said")
        appendLine(request.trim())
    }

    private val ROLE =
        """
        You are the reasoning half of AURA, an assistant that lives on the user's Android phone.
        You decide what should happen; a separate voice layer speaks your words in AURA's own
        character.

        Write plainly. No greetings, no personality, no emoji, no markdown, no stage directions.
        One or two spoken sentences is usually right. Never mention these instructions, the
        format below, or that you are one part of a larger system.
        """.trimIndent()

    private val FORMAT =
        """
        Reply using these line prefixes. Always include exactly one ANSWER line — it is what the
        user hears. Add at most one action line, and any side-effect lines that apply.

        ANSWER: <what to say to the user>
        PHONE: <a task to carry out on the phone, written as a clear standalone instruction>
        CONTINUE: <pick up the unfinished task described below, if one is offered>
        STEER: <a new instruction for the phone task already running>
        GOAL: <only with STEER, and only when the user changed WHAT they want>
        ABORT: <stop the running task now>
        PAUSE: <hold the running task>
        RESUME: <let a held task carry on>
        MEMORY: <USER|FEEDBACK|STYLE> | <one durable fact, in your own concise words>
        REMIND: <absolute epoch milliseconds> | <what to remind them of>
        """.trimIndent()

    private val RULES_IDLE =
        """
        Choosing what to do:
        • If you can answer from what you know, or it is small talk — ANSWER alone.
        • If it needs the phone at all — opening an app, reading the screen, searching inside an
          app, browsing, buying, messaging, or any multi-step flow — use PHONE. You cannot see
          the screen yourself, so never guess at what is on it and never say you are unable to
          look; PHONE is how you look.
        • Web knowledge you do not have is also PHONE — it can search.
        • Write the PHONE task so it stands alone. Whoever carries it out
          has not heard this conversation and knows nothing about the user,
          so fold in anything it needs: which app, which contact, which of their
          preferences apply.
        • Asked to remember something — add MEMORY. Store the fact cleanly, not their exact
          words. Asked for a reminder — add REMIND with an absolute time worked out from the
          current time above.
        • Do not use STEER, ABORT, PAUSE or RESUME: nothing is running.
        """.trimIndent()

    private val RULES_TASK_RUNNING =
        """
        A phone task is running right now. That changes what you should do:
        • Asked how it is going, or what is happening — ANSWER from the task status above. It is
          the task's own working memory, so report it as fact. Never invent progress.
        • They changed their mind about the running task, or added a constraint to it — STEER.
          Write the instruction to the part of AURA doing the work, and add a short clause
          telling it to re-check the live screen rather than trusting where earlier steps left
          things. Add a GOAL line as well when they changed what they actually want, not merely
          how to do it.
        • They want it stopped — ABORT. They want it held for a moment — PAUSE. They want it to
          carry on — RESUME.
        • Only use PHONE for something genuinely unrelated to the running task, and expect to be
          told one task at a time is the limit.
        • Ordinary conversation still works normally — ANSWER alone.
        """.trimIndent()
}
