package com.aura.aura_ui.agentcore

/**
 * Ranks tools for `find_tools` by the query's words in each tool's name and description.
 *
 * - Contract: pure. At most [MAX_HITS] tools, best first; a tool matching no word is left out.
 *   Name matches count double, since connector names carry the app and verb (`GMAIL_SEND_EMAIL`).
 * - ponytail: word overlap, no embeddings. Upgrade path: the recall index of phase 7, if the
 *   eval shows misses.
 */
object ToolSearch {
    const val MAX_HITS = 8

    fun rank(query: String, specs: List<ToolSpec>): List<ToolSpec> {
        val words = words(query).filter { it.length > 1 }.toSet()
        if (words.isEmpty()) return emptyList()
        return specs.map { spec ->
            val name = words(spec.name).toSet()
            val text = words(spec.descriptor.description).toSet()
            spec to words.sumOf { w -> (if (name.any { it.startsWith(w) }) 2 else 0) + (if (w in text) 1 else 0) }.toInt()
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(MAX_HITS).map { it.first }
    }

    /** A description's first sentence, short. */
    fun hint(description: String): String =
        description.substringBefore(". ").substringBefore('\n').take(HINT_CHARS).trim()

    private fun words(text: String) = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private const val HINT_CHARS = 120
}
