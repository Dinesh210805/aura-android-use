package com.aura.aura_ui.agent.conversation

/**
 * Barge-in tail gate (LV11).
 *
 * When the user speaks over the model, the server reports `interrupted` and we cut the speaker.
 * But the cancelled turn's audio frames are ALREADY in flight over the socket: they arrive a few
 * milliseconds later, get offered to the playback queue, restart the drain, and the assistant
 * audibly keeps talking after being cut off — the "it doesn't stop when I interrupt" complaint.
 * Clearing the queue at interrupt time cannot fix this, because the offending frames had not
 * arrived yet.
 *
 * So inbound audio is dropped for a short window after each interrupt. The window is safely
 * shorter than [CompanionConfig.SILENCE_DURATION_MS]: the server only starts the NEXT reply after
 * that much user silence, so a genuine follow-up reply can never be clipped by this gate.
 *
 * Pure and clock-injected so the behaviour is unit-testable without audio hardware, mirroring
 * [ReconnectPolicy] and [LiveProtocol].
 */
class InterruptGate(private val graceMs: Long = DEFAULT_GRACE_MS) {

    /** Wall-clock instant at which inbound audio becomes acceptable again. */
    @Volatile
    private var openAtMs = 0L

    /** Server reported `interrupted` — start (or restart) the drop window. */
    fun onInterrupted(nowMs: Long) {
        openAtMs = nowMs + graceMs
    }

    /**
     * Local (on-device VAD) barge-in — the server has NOT been told anything yet and will keep
     * streaming the dead turn until it notices the user, so this window must be much longer than
     * the server-interrupt one. It is a deadline, not a fixed wait: [clear] reopens the gate as
     * soon as the killed turn's `generationComplete` lands, so the next reply is never clipped.
     */
    fun onLocalBargeIn(nowMs: Long, holdMs: Long = LOCAL_BARGE_IN_HOLD_MS) {
        openAtMs = nowMs + holdMs
    }

    /** The dead turn ended (generationComplete/turnComplete) — audio is trustworthy again. */
    fun clear() {
        openAtMs = 0L
    }

    /** True while [nowMs] still falls inside the drop window. */
    fun shouldDrop(nowMs: Long): Boolean = nowMs < openAtMs

    /** Clear the window (new session / capture restart). */
    fun reset() {
        openAtMs = 0L
    }

    companion object {
        /**
         * Long enough to swallow a realistic in-flight tail, comfortably shorter than the
         * 600 ms end-of-speech silence the server waits for before generating the next reply.
         */
        const val DEFAULT_GRACE_MS = 350L

        /**
         * Upper bound on how long a locally-detected barge-in mutes the model. The server keeps
         * generating the cancelled turn until it hears the user, so this is deliberately long —
         * but it is only a backstop, because [clear] normally reopens the gate much sooner.
         */
        const val LOCAL_BARGE_IN_HOLD_MS = 2_000L
    }
}
