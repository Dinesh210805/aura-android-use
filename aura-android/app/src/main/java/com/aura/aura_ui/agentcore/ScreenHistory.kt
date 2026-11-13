package com.aura.aura_ui.agentcore

/**
 * Every screen one task saw, like a git log: a new screen is a commit (`S3`), a changes-only
 * look at the same screen is a diff on it (`S3.1`). The model reads it through the `screens`,
 * `screen` and `screen_diff` tools instead of going back to a screen just to read it again.
 *
 * - Contract: thread-safe; one per turn ([TurnState.screens]). Holds the payload text the model
 *   was shown, nothing more, so it costs no extra phone reads.
 * - Why the caption is the server's `SEEN:` line: it already names the app and the most
 *   prominent labels, and costs the model no output tokens.
 * - Why it is never put in the prompt whole: at ~700 tokens a screen, a long task would blow the
 *   context. The model asks for what it needs.
 * - Change together: `ScreenPayload.seenLine` (`:mcp-server`), which writes the caption.
 */
class ScreenHistory {
    data class Entry(val id: String, val caption: String, val text: String, val diff: Boolean)

    private val entries = mutableListOf<Entry>()
    private var commits = 0
    private var diffs = 0
    private val bodies = HashSet<Int>()

    /**
     * How many screens with content not seen before in this task. A run going round in circles
     * (A, B, A, B) records new ids but no new content, so this is what stuck detection counts.
     */
    @Volatile
    var distinct: Int = 0
        private set

    /** Record a screen payload; returns its id, or null when [text] is not a screen. */
    @Synchronized
    fun record(text: String): String? {
        val caption = text.lineSequence().firstOrNull { it.startsWith(SEEN) }?.removePrefix(SEEN)?.trim() ?: return null
        val diff = ContextAssembler.isDiffScreen(text) && commits > 0
        val id = if (diff) "S$commits.${++diffs}" else "S${++commits}".also { diffs = 0 }
        entries += Entry(id, caption, text, diff)
        val body = text.lines().filterNot(::isHeader)
        if (!body.any { NO_CHANGES in it } && bodies.add(body.hashCode())) distinct++
        return id
    }

    /** One line per screen, newest last; only those whose caption or text contains [query]. */
    @Synchronized
    fun log(query: String?): String {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val hits = entries.filter { q == null || q in it.caption.lowercase() || q in it.text.lowercase() }
        if (hits.isEmpty()) return if (q == null) "No screens yet." else "No screen mentions \"$query\"."
        return hits.takeLast(MAX_LOG).joinToString("\n") { "${it.id}${if (it.diff) " (changes)" else ""}  ${it.caption}" }
    }

    /** The first and last screen ids, e.g. "S1..S14", or null before any screen. */
    @Synchronized
    fun span(): String? = entries.takeIf { it.isNotEmpty() }?.let { "${it.first().id}..${it.last().id}" }

    @Synchronized
    fun show(id: String): String? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }?.text

    /** Lines only in [a] as `-`, lines only in [b] as `+`, in order. Null when either id is unknown. */
    @Synchronized
    fun diff(a: String, b: String): String? {
        val from = show(a)?.lines() ?: return null
        val to = show(b)?.lines() ?: return null
        val out = lineDiff(from.filterNot(::isHeader), to.filterNot(::isHeader))
        return if (out.isEmpty()) "No differences between $a and $b." else "--- $a\n+++ $b\n" + out.joinToString("\n")
    }

    private fun isHeader(line: String) = line.startsWith(SEEN) || line.startsWith("SCREEN ")

    private companion object {
        const val SEEN = "SEEN:"
        const val NO_CHANGES = "NO CHANGES since your last look"
        const val MAX_LOG = 60

        /**
         * A plain longest-common-subsequence line diff.
         * ponytail: O(n·m) table; a screen is a few hundred lines at most. Myers' algorithm if a
         * payload ever gets past a few thousand lines.
         */
        fun lineDiff(a: List<String>, b: List<String>): List<String> {
            val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            val out = mutableListOf<String>()
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) when {
                a[i] == b[j] -> { i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> out += "- ${a[i++]}"
                else -> out += "+ ${b[j++]}"
            }
            while (i < a.size) out += "- ${a[i++]}"
            while (j < b.size) out += "+ ${b[j++]}"
            return out
        }
    }
}
