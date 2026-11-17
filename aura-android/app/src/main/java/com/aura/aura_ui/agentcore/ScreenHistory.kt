package com.aura.aura_ui.agentcore

import com.aura.mcp.tools.ScreenSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Every screen one task went through, as a plain timeline: `S1`, `S2`, `S3`, in time order.
 * Screens come from two places: the model's own looks ([record]) and AURA's silent reads after
 * every screen change ([passed], from [ScreenWatcher]). Both share one numbering, so the
 * `screens`, `screen` and `screen_diff` tools also cover screens the model never read.
 *
 * - Contract: thread-safe; one per turn ([TurnState.screens]). Nothing is guessed when saving: the
 *   only rules are "same content as the newest entry is not saved again" and "same content as an
 *   earlier entry is linked to it" ([Entry.sameAs]). Grouping screens into files is a display
 *   concern (`ScreenFiles`), so a wrong group can never make an id wrong.
 * - [Entry.full] is the whole screen when a snapshot was taken with it, so a changes-only look
 *   can still be opened whole.
 * - Why the caption is the server's `SEEN:` line: it already names the app and the most
 *   prominent labels, and costs the model no output tokens.
 * - Why it is never put in the prompt whole: at ~700 tokens a screen, a long task would blow the
 *   context. The model asks for what it needs.
 * - Why som_ids on a passed screen are not tappable: a snapshot numbers them fresh, outside the
 *   cache `tap` resolves against. The `screen` tool says so.
 * - Change together: `ScreenPayload.seenLine` (`:mcp-server`), which writes the caption.
 * - Lasts for the whole chat: [encode] writes it, the next turn passes the decoded entries in as
 *   [earlier], and numbering goes on (`S4`, not `S1`). [task] is this turn's run id, so earlier
 *   tasks' screens can be told apart. Stuck detection ([distinct]) is never saved, so it counts the
 *   current task only.
 */
class ScreenHistory(
    private val task: String = "",
    earlier: List<Entry> = emptyList(),
) {
    data class Entry(
        val id: String,
        val caption: String,
        /** What the model was shown, or the snapshot text for a passed screen. */
        val text: String,
        val byModel: Boolean = true,
        /** The whole screen, from a snapshot; null when none was taken. */
        val full: String? = null,
        val pkg: String = "",
        val contentKey: Long? = null,
        val blind: Boolean = false,
        /** The earlier entry with the same content: the run came back to that screen. */
        val sameAs: String? = null,
        /** The model's own line about this screen, from the `seen` argument of the action it took on it. */
        val note: String? = null,
        /** The run id of the turn that recorded it. */
        val task: String = "",
        /** Recorded while a sensitive app was in front: saved without text or note. */
        val hidden: Boolean = false,
    )

    /** Where a model read went: its id, whether it added an entry (false: one was marked read), and the entry. */
    data class Recorded(val id: String, val added: Boolean, val entry: Entry)

    private val entries = earlier.takeLast(MAX_KEPT).toMutableList()

    /** The next id's number. Not `entries.size + 1`: trimming the oldest would reuse ids. */
    private var next = (earlier.maxOfOrNull { it.id.removePrefix("S").toIntOrNull() ?: 0 } ?: 0) + 1
    private val bodies = HashSet<Int>()

    /**
     * How many screens the model read with content not seen before in this task. A run going
     * round in circles (A, B, A, B) records new ids but no new content, so this is what stuck
     * detection counts. Passed screens never change it: a ticking clock must not hide a loop.
     */
    @Volatile
    var distinct: Int = 0
        private set

    /**
     * Record a screen the model was shown; [snap] is a silent read taken with it, when there is
     * one. Returns null when [text] is not a screen.
     */
    @Synchronized
    fun record(text: String, snap: ScreenSnapshot.Snapshot? = null, hidden: Boolean = false): Recorded? {
        val caption = captionOf(text) ?: return null
        val body = text.lines().filterNot(::isHeader)
        if (!body.any { NO_CHANGES in it } && bodies.add(body.hashCode())) distinct++
        val newest = entries.lastOrNull()
        if (snap != null && newest != null && sameContent(newest, snap)) {
            // It may be the last screen of an earlier task: it now belongs to this one too.
            val marked = newest.copy(byModel = true, task = task)
            entries[entries.lastIndex] = marked
            return Recorded(marked.id, added = false, entry = marked)
        }
        val entry = append(caption, text, byModel = true, snap, hidden)
        return Recorded(entry.id, added = true, entry = entry)
    }

    /** Record a screen nobody asked to read; null when it repeats the newest entry. */
    @Synchronized
    fun passed(snap: ScreenSnapshot.Snapshot, hidden: Boolean = false): Entry? {
        val newest = entries.lastOrNull()
        if (newest != null && sameContent(newest, snap)) return null
        return append(captionOf(snap.text).orEmpty(), snap.text, byModel = false, snap, hidden)
    }

    /**
     * The history for the chat's screens file: at most [MAX_KEPT] entries, each text the whole
     * screen cut to [MAX_KEPT_CHARS], a hidden entry with neither text nor note.
     */
    @Synchronized
    fun encode(): String = Json.encodeToString(
        ListSerializer(Saved.serializer()),
        entries.takeLast(MAX_KEPT).map {
            Saved(
                it.id, it.caption, if (it.hidden) "" else (it.full ?: it.text).take(MAX_KEPT_CHARS), it.byModel, it.pkg,
                it.contentKey, it.blind, it.sameAs, if (it.hidden) null else it.note, it.task, it.hidden,
            )
        },
    )

    /**
     * Put the model's [note] on the newest screen: the one it decided its action on. A later note
     * on the same screen replaces the earlier one. Does nothing before any screen.
     */
    @Synchronized
    fun annotate(note: String) {
        val newest = entries.lastOrNull() ?: return
        entries[entries.lastIndex] = newest.copy(note = note)
    }

    private fun append(caption: String, text: String, byModel: Boolean, snap: ScreenSnapshot.Snapshot?, hidden: Boolean): Entry {
        val earlier = snap?.takeIf { !it.blind }?.let { s -> entries.lastOrNull { !it.blind && it.pkg == s.pkg && it.contentKey == s.contentKey } }
        val entry = Entry(
            id = "S${next++}", caption = caption, text = text, byModel = byModel, full = snap?.text,
            pkg = snap?.pkg.orEmpty(), contentKey = snap?.contentKey, blind = snap?.blind == true, sameAs = earlier?.id,
            task = task, hidden = hidden,
        )
        entries += entry
        if (entries.size > MAX_KEPT) entries.removeAt(0)
        return entry
    }

    /** Same screen as [e]: equal content in the same app, or both blind there (their keys mean nothing). */
    private fun sameContent(e: Entry, snap: ScreenSnapshot.Snapshot): Boolean = when {
        e.pkg != snap.pkg -> false
        snap.blind || e.blind -> snap.blind && e.blind
        else -> e.contentKey == snap.contentKey
    }

    /**
     * With no [query]: the current task's screens, one line each, `S3 · caption · note: …`.
     * With one: every screen of the chat whose text, caption or note holds it (any case), newest
     * last, as `S3: <matching line>`, at most [LINES_PER_HIT] lines a screen and [MAX_LOG] in all.
     * Hits from an earlier task say so; a hidden screen never matches.
     */
    @Synchronized
    fun log(query: String?): String {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (q == null) {
            val current = entries.filter { it.task == task }
            if (current.isEmpty()) return "No screens yet."
            return current.takeLast(MAX_LOG).joinToString("\n") {
                it.id + (it.sameAs?.let { s -> " = $s" } ?: "") + (if (it.byModel) "" else " (passed, not read)") + " · " + it.caption +
                    it.note?.let { n -> " · note: $n" }.orEmpty()
            }
        }
        val hits = entries.filterNot { it.hidden }.flatMap { e ->
            val earlier = if (e.task != task) " (earlier task)" else ""
            matchingLines(e, q).take(LINES_PER_HIT).map { "${e.id}: $it$earlier" }
        }
        if (hits.isEmpty()) return "No screen mentions \"$query\"."
        return hits.takeLast(MAX_LOG).joinToString("\n")
    }

    /** [e]'s screen lines holding [q], without the grid's borders; else its caption; and its note. */
    private fun matchingLines(e: Entry, q: String): List<String> {
        val body = (e.full ?: e.text).lines().filterNot(::isHeader).map { it.trim().trim('|').trim() }.filter { q in it.lowercase() }
        val caption = listOf(e.caption).takeIf { body.isEmpty() && q in e.caption.lowercase() }.orEmpty()
        val note = e.note?.takeIf { q in it.lowercase() }?.let { listOf("note: $it") }.orEmpty()
        return body + caption + note
    }

    /** The first and last screen ids, e.g. "S1..S14", or null before any screen. */
    @Synchronized
    fun span(): String? = entries.takeIf { it.isNotEmpty() }?.let { "${it.first().id}..${it.last().id}" }

    /** The newest screen's `SEEN:` caption (app and top labels), or null before any. */
    @Synchronized
    fun latestCaption(): String? = entries.lastOrNull()?.caption

    /** The newest screen the task went through, whole when known, or null before any. */
    @Synchronized
    fun latest(): String? = entries.lastOrNull()?.let { it.full ?: it.text }

    /** That screen whole when known, else as the model was shown it. */
    @Synchronized
    fun show(id: String): String? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }?.let { it.full ?: it.text }

    /** Lines only in [a] as `-`, lines only in [b] as `+`, in order. Null when either id is unknown. */
    @Synchronized
    fun diff(a: String, b: String): String? {
        val from = show(a)?.lines() ?: return null
        val to = show(b)?.lines() ?: return null
        val out = screenLineDiff(from.filterNot(::isHeader), to.filterNot(::isHeader))
        return if (out.isEmpty()) "No differences between $a and $b." else "--- $a\n+++ $b\n" + out.joinToString("\n")
    }

    private fun captionOf(text: String) = text.lineSequence().firstOrNull { it.startsWith(SEEN) }?.removePrefix(SEEN)?.trim()

    private fun isHeader(line: String) = line.startsWith(SEEN) || line.startsWith("SCREEN ")

    /** One entry as the chat's screens file holds it. `text` is the whole screen, cut. */
    @Serializable
    private data class Saved(
        val id: String,
        val caption: String,
        val text: String,
        val byModel: Boolean,
        val pkg: String,
        val contentKey: Long?,
        val blind: Boolean,
        val sameAs: String?,
        val note: String?,
        val task: String,
        val hidden: Boolean,
    )

    companion object {
        private const val SEEN = "SEEN:"
        private const val NO_CHANGES = "NO CHANGES since your last look"
        private const val MAX_LOG = 60
        private const val LINES_PER_HIT = 3

        /** Entries kept per chat, oldest dropped first. */
        const val MAX_KEPT = 300

        /** Characters of each screen written to the file; about 2.5 MB at [MAX_KEPT] entries. */
        const val MAX_KEPT_CHARS = 8_000

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The entries [encode] wrote, for the next turn's [ScreenHistory].
         * - Fails: open. A missing, cut-off or unreadable file gives an empty list, never a failed turn.
         */
        fun decode(stored: String?): List<Entry> = stored?.let {
            runCatching { json.decodeFromString(ListSerializer(Saved.serializer()), it) }.getOrNull()
        }.orEmpty().map {
            Entry(
                id = it.id, caption = it.caption, text = it.text, byModel = it.byModel, pkg = it.pkg,
                contentKey = it.contentKey, blind = it.blind, sameAs = it.sameAs, note = it.note, task = it.task, hidden = it.hidden,
            )
        }
    }
}

/**
 * A plain longest-common-subsequence line diff: lines only in [a] as `- `, only in [b] as `+ `.
 * Shared by the `screen_diff` tool and the run page's Screens view.
 * ponytail: O(n·m) table; a screen is a few hundred lines at most. Myers' algorithm if a
 * payload ever gets past a few thousand lines.
 */
internal fun screenLineDiff(a: List<String>, b: List<String>): List<String> {
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
