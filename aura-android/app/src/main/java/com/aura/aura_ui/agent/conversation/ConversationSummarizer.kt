package com.aura.aura_ui.agent.conversation

import org.json.JSONObject

/**
 * What a session summarization yields: a second-person episode summary, 0–3 durable user facts,
 * and 0–2 observations about HOW the user speaks. [style] is what lets AURA keep talking the way
 * the user talks across sessions — without it the model rediscovers their language and register
 * from scratch every time it connects.
 */
data class ConversationSummary(
    val summary: String,
    val facts: List<String>,
    val style: List<String> = emptyList(),
)

/** Summarizes a rendered transcript. Returns null on empty input, network error, or bad output. */
interface ConversationSummarizer {
    /** Full end-of-session summary: episode summary + durable facts. */
    suspend fun summarize(transcript: String): ConversationSummary?

    /**
     * Mid-session checkpoint: extract ONLY the durable facts worth remembering so far (cheaper,
     * shorter output than a full summary). Used for partial checkpointing so important info
     * survives a connection drop. Empty list on failure/nothing worth saving.
     */
    suspend fun extractFacts(transcript: String): List<String>
}

/** Pure request-prompt builder — kept separate so it is unit-tested without the network. */
object SummaryPromptBuilder {
    fun prompt(transcript: String): String =
        """
        Summarize this voice conversation between a user and their assistant AURA.
        Reply ONLY with JSON: {"summary": string, "facts": string[], "style": string[]}.
        - "summary": <=120 words, second person ("You asked about..."), what was discussed.
          Keep any upcoming event they mentioned with its day (an interview Thursday, a trip
          next week), so it can be followed up later.
        - "facts": 0-3 DURABLE facts about the user worth remembering long-term (names,
          preferences, ongoing projects). Omit anything transient, trivial, or sensitive
          (passwords, codes, financial/medical details). Empty array if none.
        - "style": 0-2 short notes on HOW the user speaks, so the assistant can talk back the
          same way. Name the languages and whether they mix them inside one sentence (for
          example "mixes Tamil and English in the same sentence, casual spoken Tamil, not
          literary"), plus register, sentence length, or energy if distinctive. Describe only
          what this transcript shows. Empty array if nothing clear.

        Conversation:
        $transcript
        """.trimIndent()

    /** Facts-only checkpoint prompt — short output; the model returns just the durable facts. */
    fun factsPrompt(transcript: String): String =
        """
        From this ongoing voice conversation, extract 0-4 DURABLE facts about the user worth
        remembering long-term (names, preferences, relationships, ongoing projects, decisions).
        Omit anything transient, trivial, or sensitive (passwords, codes, financial/medical).
        Reply ONLY with JSON: {"facts": string[]}. Empty array if nothing worth saving.

        Conversation so far:
        $transcript
        """.trimIndent()
}

/** Pure parser for the model's JSON reply; tolerant of code fences and missing facts. */
object ConversationSummaryParser {
    fun parse(raw: String): ConversationSummary? {
        val fenced = raw.substringAfter("```json", raw).substringBefore("```")
        val body = (if (fenced.isBlank()) raw else fenced).trim()
        val start = body.indexOf('{')
        if (start < 0) return null
        val json = body.substring(start).substringBeforeLast('}') + "}"
        return runCatching {
            val o = JSONObject(json)
            val summary = o.optString("summary").trim()
            if (summary.isEmpty()) return null
            ConversationSummary(summary, o.strings("facts"), o.strings("style"))
        }.getOrNull()
    }

    private fun JSONObject.strings(key: String): List<String> =
        optJSONArray(key)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        } ?: emptyList()

    /** Parse a facts-only checkpoint reply `{"facts": [...]}`; empty list on garbage. */
    fun parseFacts(raw: String): List<String> {
        val fenced = raw.substringAfter("```json", raw).substringBefore("```")
        val body = (if (fenced.isBlank()) raw else fenced).trim()
        val start = body.indexOf('{')
        if (start < 0) return emptyList()
        val json = body.substring(start).substringBeforeLast('}') + "}"
        return runCatching {
            JSONObject(json).optJSONArray("facts")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
