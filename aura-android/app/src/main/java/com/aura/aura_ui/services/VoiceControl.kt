package com.aura.aura_ui.services

/**
 * Spec 2026-07-31 — taking the wheel by voice.
 *
 * "Pause" spoken aloud is the most natural way to grab control back, and the only one
 * that works hands-free or from across the room. It drives the same [ControlLockStore]
 * as a touch does, so this stays **one lock** rather than becoming a second parallel
 * mechanism — the mistake the spec warns about throughout.
 *
 * ### The whole difficulty: a command versus a task
 *
 * "pause" means *stop what you are doing*. "pause the video" is a job **for** the agent.
 * Both directions of error are costly: swallow a task and the agent goes deaf to real
 * requests ("why won't it ever pause my music?"); miss a command and the user is left
 * saying "stop, STOP" at a phone that keeps typing.
 *
 * The rule is deliberately blunt — **only a bare utterance counts.** A command with an
 * object is a task. Politeness words are stripped first, because people say "ok stop"
 * and "please pause" constantly and ignoring an explicit instruction to stop is
 * unacceptable at exactly the moment it is given.
 *
 * Blunt beats clever here. Anything fuzzier would need an LLM round trip on every
 * utterance, and this must work *while the agent is paused* — i.e. when it may be doing
 * nothing at all.
 */
internal object VoiceControl {

    enum class Command {
        /** Step aside; the run is expected to continue later. */
        PAUSE,

        /** Same lock, stronger intent — the user wants it to stop now. */
        STOP,

        /** Hand the wheel back. */
        RESUME,

        /**
         * Abandon the paused task entirely.
         *
         * Without this the user is trapped between a stale task they no longer want and a new
         * one the pause refuses to start — so "drop it" is not a convenience, it is the exit
         * from a state that would otherwise need a force-quit.
         */
        DROP,
    }

    /** Stripped before matching so "ok stop" and "please pause" still register. */
    private val FILLER = setOf("ok", "okay", "hey", "aura", "please", "now", "just", "can", "you")

    private val PAUSE_WORDS = setOf("pause", "hold on", "wait")
    private val STOP_WORDS = setOf("stop", "cancel", "abort", "halt")
    private val RESUME_WORDS = setOf("continue", "resume", "carry on", "go on", "go ahead", "keep going")
    private val DROP_WORDS = setOf("drop it", "forget it", "cancel that", "never mind", "nevermind", "drop that")

    fun parse(transcript: String): Command? {
        val cleaned = transcript
            .lowercase()
            // Real STT output arrives capitalised, padded and punctuated; a rule this
            // small is worthless if "Pause." misses.
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in FILLER }
            .joinToString(" ")

        if (cleaned.isBlank()) return null

        return when (cleaned) {
            in PAUSE_WORDS -> Command.PAUSE
            in STOP_WORDS -> Command.STOP
            in RESUME_WORDS -> Command.RESUME
            in DROP_WORDS -> Command.DROP
            // Anything left over is an object ("pause the video"), which makes it a task.
            else -> null
        }
    }
}
