package com.aura.aura_ui.agent.conversation

import android.util.Log
import com.aura.aura_ui.agent.memory.MemoryService
import com.aura.aura_ui.agent.memory.MemoryType
import com.aura.aura_ui.conversation.ConversationMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The diary for the overlay's conversations — the agent-lane twin of
 * [CompanionLiveController]'s `summarizeSession`, which only ever ran while Live was on.
 *
 * One episode per CONVERSATION, not per run: a chat of eight commands is one day-in-the-life line,
 * not eight summaries. Per-run task lines are [com.aura.aura_ui.agent.AuraAgent]'s job.
 *
 * [scope] must outlive the overlay: the summary is a network round-trip that usually lands after
 * the window that triggered it is gone. The default scope is never cancelled for that reason.
 */
class AgentLaneEpisodeWriter(
    private val summarizer: ConversationSummarizer,
    private val memory: MemoryService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** How an extracted fact lands. The overlay passes a [com.aura.aura_ui.agent.memory.FactReconciler]. */
    private val saveFact: suspend (String) -> Unit = { memory.save(MemoryType.USER, it) },
) {
    /** Id of the newest message already accounted for — the overlay keeps its chat across shows. */
    private var lastSeenId: String? = null

    /** Set when something other than the person's own agent-lane chat touched this conversation. */
    @Volatile private var excluded = false

    /**
     * Keep the current conversation out of the diary. For eval / AndroidWorld goals (a benchmark
     * sweep is not the user's day) and for Live sessions, which write their own episodes.
     */
    fun excludeCurrentConversation() {
        excluded = true
    }

    /** Called where a conversation ends: overlay hidden, chat cleared, service destroyed. */
    fun onConversationEnded(messages: List<ConversationMessage>) {
        val fresh = unseen(messages, lastSeenId)
        messages.lastOrNull()?.let { lastSeenId = it.id }
        val skip = excluded
        excluded = false
        if (skip) return
        val transcript = transcriptOrNull(fresh) ?: return
        scope.launch {
            runCatching {
                summarizer.summarize(transcript)?.let { s ->
                    if (s.summary.isNotBlank()) memory.appendEpisode(s.summary)
                    s.facts.take(MAX_FACTS).forEach { saveFact(it) }
                    s.style.take(MAX_STYLE_NOTES).forEach { memory.save(MemoryType.STYLE, it) }
                }
            }.onFailure { Log.w(TAG, "episode summarize failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "AgentLaneEpisode"

        /** Same floor as Live: a one-line exchange is a task-log line, not a diary entry. */
        const val MIN_USER_TURNS = 2
        const val MAX_FACTS = 4
        const val MAX_STYLE_NOTES = 2

        /** Messages after [lastSeenId]; all of them when it is null or gone (chat was cleared). */
        fun unseen(messages: List<ConversationMessage>, lastSeenId: String?): List<ConversationMessage> {
            val idx = lastSeenId?.let { id -> messages.indexOfLast { it.id == id } } ?: -1
            return if (idx < 0) messages else messages.drop(idx + 1)
        }

        /** "User: …" / "AURA: …" lines, or null below the turn floor. Partial/streaming text is noise. */
        fun transcriptOrNull(messages: List<ConversationMessage>): String? {
            val settled = messages.filter { !it.isPartial && !it.isStreaming && it.text.isNotBlank() }
            if (settled.count { it.isUser } < MIN_USER_TURNS) return null
            return settled.joinToString("\n") { "${if (it.isUser) "User" else "AURA"}: ${it.text.trim()}" }
        }
    }
}
