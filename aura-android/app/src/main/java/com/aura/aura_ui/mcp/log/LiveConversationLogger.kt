package com.aura.aura_ui.mcp.log

import android.content.Context
import java.io.File

/**
 * On-disk log for the **conversation plane** — what the user said, what AURA said, when, and
 * what went missing.
 *
 * ### Why this exists
 *
 * The action plane has had a full trace since Phase 10B ([AgentRunLogger]): every tool call,
 * every LLM round-trip, screenshots, timings. The conversation plane had **nothing** — only
 * an in-memory `SessionTranscript` with no timestamps that was cleared whenever a new
 * conversation began. So when AURA said "I hit a snag" while a task was in fact still
 * running, there was no record of the sentence, when it was said, or what preceded it.
 *
 * This writes a third session `source` ("live") into the same `mcp_logs` store the other two
 * planes use, so it inherits the Settings → Logs screen, the HTML renderer, PDF export and
 * the retention worker for free rather than growing a parallel log system.
 *
 * ### What gets recorded
 *
 * Everything, including absences — see [Utterance.kind]. A blank transcription, a barge-in,
 * a reconnect and a handoff to the action plane are all entries. The rule is that a silent
 * gap in the log must never be ambiguous between "nothing happened" and "something happened
 * and nobody wrote it down".
 *
 * File ownership belongs to [SessionLogWriter], shared with [AgentRunLogger] so a task this
 * conversation starts lands in the same session rather than a second one. Appends are frequent
 * but tiny (a sentence), and correctness under a process kill matters more here than write
 * amplification — a conversation only flushed at the end is exactly the one lost when the app
 * dies.
 */
class LiveConversationLogger(
    context: Context,
    rootDir: File = File(context.applicationContext.filesDir, "mcp_logs").apply { mkdirs() },
    private val writer: SessionLogWriter = SessionLogWriter(rootDir, TAG),
) {

    /**
     * Whether a conversation has been declared, and whether it has since finished.
     *
     * A straggler arriving after the end must join the conversation it belongs to, never
     * declare a new one — a socket dying mid-greeting, and the teardown note itself, each
     * used to mint a session directory holding a single line.
     */
    private enum class Phase { NEW, DECLARED, ENDED }

    @Volatile private var phase = Phase.NEW

    /** Open a conversation session. [modelLabel] identifies the Live model in the Logs UI. */
    fun startSession(modelLabel: String?) {
        phase = Phase.DECLARED
        open(modelLabel)
        // Publish the writer so a phone task this conversation asks for records into the same
        // session rather than a second one nobody thinks to open. See [LiveSessionScope].
        LiveSessionScope.setConversation(writer)
    }

    /**
     * Append one utterance. Safe before [startSession] — a conversation that begins before the
     * session is declared still gets logged, because dropping the opening turns is the same
     * failure this class was built to remove.
     *
     * Lifecycle notes never bring a session into existence by themselves. "Started" and "ended"
     * describe a conversation; they are not one, and a directory containing only those two is a
     * Logs card for something that never happened.
     */
    fun record(utterance: Utterance) {
        if (phase == Phase.NEW) {
            phase = Phase.DECLARED
            open(modelLabel = null)
        }
        writer.edit(materialize = utterance.kind != "lifecycle") { session, _ ->
            session.utterances.add(utterance)
        }
    }

    fun endSession(reason: String) {
        phase = Phase.ENDED
        LiveSessionScope.setConversation(null)
        writer.close {
            it.endedAtMillis = System.currentTimeMillis()
            it.endReason = reason
        }
    }

    private fun open(modelLabel: String?) {
        writer.open { sessionId, now ->
            SessionLog(
                sessionId = sessionId,
                startedAtMillis = now,
                agentLabel = modelLabel,
                tokenId = null,
                source = SOURCE_LIVE,
            )
        }
    }

    companion object {
        private const val TAG = "LiveConversationLog"

        /** The third session source, alongside "mcp" and "agent". */
        const val SOURCE_LIVE = "live"
    }
}
