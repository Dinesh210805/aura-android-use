package com.aura.aura_ui.voice

import kotlin.math.pow

/**
 * Splits a reply into the chunks `PiperSpeaker` synthesizes one at a time.
 *
 * - Contract: markdown stripped, never blank, never spanning two sentences. Chunk `i` is at most
 *   `FIRST_MAX_CHARS * GROWTH^i` chars (capped at [MAX_CHARS]), cut at the last clause break that
 *   fits, else the last word break. Joining the chunks with spaces gives the cleaned text back.
 * - Why: synthesis is pipelined (chunk i+1 is synthesized
 *   while chunk i plays). A short first chunk is what makes AURA start talking fast; growing each
 *   limit by no more than [GROWTH] keeps the next chunk ready before the current one runs out, so a
 *   long sentence doesn't stall in the middle.
 */
internal object SpeechChunker {
    private const val FIRST_MAX_CHARS = 30 // ≈ 2 s of speech
    private const val MAX_CHARS = 180
    private const val GROWTH = 1.2 // safe while the engine's real-time factor stays under ~0.8 (slow phones)
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+|\\n+")
    private val CLAUSE_END = Regex("(?<=[,;:])\\s+")
    private val MARKDOWN = Regex("[*_#`>|]+|\\[([^\\]]*)]\\([^)]*\\)")

    fun split(text: String): List<String> {
        val clean = MARKDOWN.replace(text) { it.groupValues[1] }
        val out = mutableListOf<String>()
        for (sentence in clean.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }) {
            var rest = sentence
            while (rest.isNotEmpty()) {
                val limit = limitFor(out.size)
                if (rest.length <= limit) { out += rest; break }
                val cut = breakAt(rest, limit)
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
        }
        return out
    }

    internal fun limitFor(index: Int): Int =
        minOf(MAX_CHARS.toDouble(), FIRST_MAX_CHARS * GROWTH.pow(index)).toInt()

    /** Where to cut [s]: the last clause break within [limit], else the last word break, else the first one past it. */
    private fun breakAt(s: String, limit: Int): Int {
        CLAUSE_END.findAll(s).map { it.range.first }.lastOrNull { it in 1..limit }?.let { return it }
        val before = s.lastIndexOf(' ', limit)
        if (before > 0) return before
        return s.indexOf(' ', limit).takeIf { it > 0 } ?: s.length
    }
}
