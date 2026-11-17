package com.aura.aura_ui.agentcore

import android.content.Context
import com.aura.aura_ui.agent.memory.PiiFirewall

/**
 * The words an interface is made of (settings, add, cart, profile…), used as an allowlist: text
 * from a screen leaves the phone for the screen review ([ScreenReview]) only when every word is
 * one of these or part of an installed app's name.
 *
 * - Why an allowlist and not a scrubber: a scrubber can't tell a plain username (`johndoe`) or a
 *   contact's name from a word. Anything not known to be an interface word is held back.
 * - Reads: `assets/ui_words.txt`, one lower-case English word a line. Words that are also common
 *   given names (Grace, Will, Rose, Mark) are left out of it on purpose; `ScreenReviewTest`
 *   checks 50 of them. To add a word, add a line; to add a language, add its words the same way.
 */
class UiWords(private val words: Set<String>) {

    /** True when every word of [label] is an interface word or part of a whole app name in [apps]. */
    fun keeps(label: String, apps: Collection<String>): Boolean {
        val tokens = TOKEN.findAll(label.lowercase()).map { it.value }.toList()
        return tokens.isNotEmpty() && known(tokens, appTokens(apps)).all { it }
    }

    /**
     * [note] with personal details scrubbed and every word that is not an interface word or part
     * of an app name replaced by `[name]` ("opening johndoe's profile" → "opening [name]'s profile").
     */
    fun mask(note: String, apps: Collection<String>): String {
        val scrubbed = PiiFirewall.scrub(note)
        val matches = LETTERS.findAll(scrubbed).toList()
        val keep = known(matches.map { it.value.lowercase() }, appTokens(apps))
        val out = StringBuilder()
        var at = 0
        matches.forEachIndexed { i, m ->
            out.append(scrubbed, at, m.range.first).append(if (keep[i] || m.value.length == 1) m.value else NAME)
            at = m.range.last + 1
        }
        out.append(scrubbed, at, scrubbed.length)
        return REPEATED_NAME.replace(out, NAME)
    }

    /** For each token, whether it is a UI word or inside a run of tokens that spells an app's name. */
    private fun known(tokens: List<String>, apps: List<List<String>>): List<Boolean> {
        val ok = BooleanArray(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val app = apps.filter { a -> a.size <= tokens.size - i && a == tokens.subList(i, i + a.size) }.maxByOrNull { it.size }
            if (app != null) {
                repeat(app.size) { ok[i + it] = true }
                i += app.size
            } else {
                ok[i] = tokens[i] in words
                i++
            }
        }
        return ok.toList()
    }

    private fun appTokens(apps: Collection<String>) =
        apps.map { app -> TOKEN.findAll(app.lowercase()).map { it.value }.toList() }.filter { it.isNotEmpty() }

    companion object {
        /** A word with its digits: a token holding a digit is never a UI word, so "Page 2" is held back. */
        private val TOKEN = Regex("[\\p{L}\\p{N}]+(?:'[\\p{L}]+)?")
        private val LETTERS = Regex("\\p{L}+")
        private const val NAME = "[name]"
        private val REPEATED_NAME = Regex("\\[name](?:\\s+\\[name])+")

        fun parse(text: String): UiWords = UiWords(text.lineSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())

        /** Fails: an unreadable asset gives an empty list, so every label is held back (closed). */
        fun load(context: Context): UiWords =
            parse(runCatching { context.assets.open("ui_words.txt").bufferedReader().use { it.readText() } }.getOrDefault(""))
    }
}
