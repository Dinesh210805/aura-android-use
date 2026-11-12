package com.aura.aura_ui.voice

/**
 * The caption for a Piper reply at a given speaker playback head: the sentence being heard, up to
 * and including the word being spoken.
 *
 * - Contract: pure. [Segment]s are the chunks in play order, each placed at its frame offset in the
 *   audio and its character offset in `chunks.joinToString(" ")` ([offsets]).
 * - Why the segment is found by the head and not by the chunk last written: synthesis runs ahead,
 *   so the next chunk is written while the previous one is still in the track's buffer. Captioning
 *   the written chunk drops the heard chunk's tail.
 * - Why the word being spoken is included: a word that appears only once it has finished lags the
 *   voice by a word, and the last word of a sentence would show only during trailing silence.
 * - ponytail: frames map to characters linearly within a chunk, so it drifts by up to a word inside
 *   long chunks. Per-word timings would need durations from the engine; sherpa-onnx's VITS output
 *   does not expose them.
 */
internal object PlaybackCaption {

    class Segment(val startFrame: Int, val frames: Int, val charStart: Int, val chars: Int)

    /** Character offset of each chunk inside `chunks.joinToString(" ")`. */
    fun offsets(chunks: List<String>): IntArray {
        var at = 0
        return IntArray(chunks.size) { i -> at.also { at += chunks[i].length + 1 } }
    }

    fun at(full: String, segments: List<Segment>, head: Int): String {
        if (head <= 0) return ""
        val seg = segments.lastOrNull { head >= it.startFrame } ?: return ""
        val played = (head - seg.startFrame).coerceIn(0, seg.frames)
        val heard = if (seg.frames == 0) seg.chars else (played.toLong() * seg.chars / seg.frames).toInt()
        val pos = (seg.charStart + heard).coerceAtMost(full.length)
        val wordEnd = full.indexOf(' ', pos).let { if (it < 0) full.length else it + 1 }
        return sentence(full, wordEnd)
    }

    /**
     * The sentence containing the [spoken]th character, cut back to whole words.
     * - Why whole words: [spoken] usually lands mid-word, and a half word on screen reads as a
     *   typo; once everything has been heard the last word is whole by definition and shown.
     */
    fun sentence(full: String, spoken: Int): String {
        if (full.isEmpty() || spoken <= 0) return ""
        val end = if (spoken >= full.length) full.length else full.lastIndexOf(' ', spoken - 1).coerceAtLeast(0)
        if (end == 0) return ""
        val head = full.take(end).trimEnd()
        val startOfSentence = SENTENCE_START.findAll(head).lastOrNull()?.range?.last?.plus(1) ?: 0
        return head.substring(startOfSentence).trim()
    }

    /**
     * Where the next sentence starts: `.!?` followed by whitespace, or a newline. A dot inside
     * `nasa.gov` or `.75` is not a sentence end.
     * - Change together: `SpeechChunker.SENTENCE_END` (same rule for where speech chunks split).
     */
    private val SENTENCE_START = Regex("[.!?]\\s+|\\n")
}
