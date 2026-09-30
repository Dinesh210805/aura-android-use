package com.aura.aura_ui.agent.conversation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aura.aura_ui.agent.memory.EncryptedMemoryService
import com.aura.aura_ui.agent.memory.FactReconciler
import okhttp3.OkHttpClient

/**
 * Conversation summarizer that reuses the AGENT'S BRAIN — the same OpenAI-compatible endpoint,
 * API key, and model the on-device agent runs. Endpoint resolution and the HTTP leg live in
 * [BrainChat], shared with [AuraBrainLane]; this class owns only the summarizing prompts and
 * the parsing of their replies. Returns null / empty on any failure so a session teardown never
 * crashes.
 */
class AgentBrainSummarizer(
    private val context: Context,
    private val httpClient: OkHttpClient = BrainChat.defaultClient(),
) : ConversationSummarizer {

    override suspend fun summarize(transcript: String): ConversationSummary? = withContext(Dispatchers.IO) {
        if (transcript.isBlank()) return@withContext null
        val reply = chat(SummaryPromptBuilder.prompt(transcript)) ?: return@withContext null
        ConversationSummaryParser.parse(reply)
    }

    override suspend fun extractFacts(transcript: String): List<String> = withContext(Dispatchers.IO) {
        if (transcript.isBlank()) return@withContext emptyList()
        val reply = chat(SummaryPromptBuilder.factsPrompt(transcript)) ?: return@withContext emptyList()
        ConversationSummaryParser.parseFacts(reply)
    }

    private suspend fun chat(prompt: String): String? {
        val brain = BrainChat.resolve(context) ?: return null
        return BrainChat.complete(brain, prompt, temperature = SUMMARY_TEMPERATURE, httpClient = httpClient)
    }

    private companion object {
        /** Summarizing is extraction, not invention — keep it tight. */
        const val SUMMARY_TEMPERATURE = 0.2
    }
}

/** One-shot prompt → reply on the agent's own brain model; null when none is configured or it fails. */
fun brainLlm(context: Context, temperature: Double = 0.0): suspend (String) -> String? {
    val client = BrainChat.defaultClient()
    return { prompt -> BrainChat.resolve(context)?.let { BrainChat.complete(it, prompt, temperature, client) } }
}

/** A [FactReconciler] deciding with the agent's own brain model — no second provider to configure. */
fun brainFactReconciler(context: Context, memory: EncryptedMemoryService): FactReconciler =
    FactReconciler(memory, brainLlm(context))
