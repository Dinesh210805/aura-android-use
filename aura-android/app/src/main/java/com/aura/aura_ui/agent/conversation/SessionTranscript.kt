package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.mcp.log.Utterance

/**
 * Thread-safe accumulator of a Live session's turns.
 *
 * Two jobs, deliberately separated:
 *
 *  - **The summarizer view** ([render], [userTurnCount], [reset]) — what
 *    [ConversationSummarizer] reads to build conversation memory. Only real, complete
 *    speech belongs here, and it is cleared whenever a new conversation starts.
 *
 *  - **The log** ([sink]) — a write-through record of *everything*, including the things
 *    the summarizer must not see. It is never cleared by [reset].
 *
 * ### Why the split (2026-08-05)
 *
 * Before this, the class was the first job only: in memory, no timestamps, gone on reset —
 * so the conversation plane had no diagnosable history at all, while the action plane had a
 * full on-disk trace. Worse, `add()` opened with `if (t.isEmpty()) return`, so a blank
 * transcription vanished without trace. Afterwards "AURA said nothing" and "AURA said
 * something nobody recorded" look identical, which is exactly the ambiguity that made the
 * reported Live desync impossible to investigate.
 *
 * So absences are now first-class: a blank turn becomes a `gap` [Utterance] naming which
 * side went missing, while still being kept out of [render] — feeding "[no transcription]"
 * to the summarizer would turn a transcriber failure into a remembered fact about the user.
 *
 * Touched from the WS-callback thread (appends) and the cleanup thread (render/count), so
 * all access is synchronized. [sink] is invoked outside the lock: it does IO, and holding a
 * conversation lock across a disk write is how a barge-in ends up waiting on a file handle.
 */
class SessionTranscript(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (Utterance) -> Unit = {},
) {
    private data class Turn(val who: String, val text: String)

    private val turns = mutableListOf<Turn>()
    private val lock = Any()

    /**
     * [spoke] answers the only question an empty [text] raises: did this speaker actually
     * make a sound? True (the default) means yes, so a blank turn is a *gap* — audio
     * happened, words did not survive. False means the caller is finalizing a turn this
     * speaker never took, and there is nothing to record at all.
     *
     * Without it, the controller's turn finalization minted an empty user turn on every
     * ordinary exchange (the user's text having already been flushed when the model began
     * replying), and 11 of the first log's 26 entries were gaps that recorded nothing.
     * A gap that fires when nothing is missing is worse than no gap at all.
     */
    fun addUser(text: String, kind: String = "speech", complete: Boolean = true, spoke: Boolean = true) =
        add("User", "user", text, kind, complete, spoke)

    fun addModel(text: String, kind: String = "speech", complete: Boolean = true, spoke: Boolean = true) =
        add("AURA", "aura", text, kind, complete, spoke)

    /**
     * Record something that happened but was not *said* — session start, a reconnect, a
     * handoff to the action plane, a dropped audio buffer.
     *
     * Log-only by design: these never reach the summarizer, because "the socket
     * reconnected" is not something the user told AURA about themselves.
     */
    fun note(speaker: String, text: String, kind: String) {
        emit(Utterance(atMillis = clock(), speaker = speaker, text = text, kind = kind, complete = true))
    }

    private fun add(
        renderAs: String,
        speaker: String,
        text: String,
        kind: String,
        complete: Boolean,
        spoke: Boolean,
    ) {
        val trimmed = text.trim()

        if (trimmed.isEmpty()) {
            // Nobody spoke and nothing was lost — this is a turn the caller is closing on
            // this speaker's behalf, not an absence.
            if (!spoke) return
            // The turn happened; the words did not survive. Record the absence, say whose
            // it was, and keep it out of the summarizer view.
            emit(
                Utterance(
                    atMillis = clock(),
                    speaker = speaker,
                    text = "[no transcription — turn produced no text]",
                    kind = "gap",
                    complete = false,
                ),
            )
            return
        }

        synchronized(lock) { turns.add(Turn(renderAs, trimmed)) }
        emit(Utterance(clock(), speaker, trimmed, kind, complete))
    }

    /** Never throws and never holds [lock]: a failing log must not break a conversation. */
    private fun emit(utterance: Utterance) {
        runCatching { sink(utterance) }
    }

    fun userTurnCount(): Int = synchronized(lock) { turns.count { it.who == "User" } }

    fun render(): String = synchronized(lock) { turns.joinToString("\n") { "${it.who}: ${it.text}" } }

    /**
     * The last [n] turns, same format as [render].
     *
     * The summarizer wants the whole conversation; the brain lane wants only enough to resolve
     * what "it" and "that one" refer to. Sending the full transcript on every turn would grow
     * the lane's prompt without bound over a long session — the same unbounded-context problem
     * the action plane already solved with eviction, arriving here by a different door.
     *
     * A non-positive [n] yields "" rather than everything: a caller asking for no context should
     * get none, not silently get the maximum.
     */
    fun recent(n: Int): String = synchronized(lock) {
        if (n <= 0) "" else turns.takeLast(n).joinToString("\n") { "${it.who}: ${it.text}" }
    }

    /** Clears the summarizer view for a fresh conversation. Does NOT clear the log. */
    fun reset() = synchronized(lock) { turns.clear() }
}
