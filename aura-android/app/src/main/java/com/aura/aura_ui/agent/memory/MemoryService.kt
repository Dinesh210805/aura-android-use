package com.aura.aura_ui.agent.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Memory taxonomy, split by HOW an entry may enter the prompt rather than by what it is about
 * (spec 2026-07-28). Before this split every episode summary and every task outcome was written as
 * [PROJECT] and rendered under a "What I remember" header, so the model was handed last week's
 * conversation narration as present-tense knowledge — a direct source of confident false reports.
 *
 * Three injection classes:
 *  - identity ([USER], [FEEDBACK]) — eligible for the unprompted session context.
 *  - style ([STYLE]) — never in the context block; it is folded into the persona instead, so the
 *    way the user speaks shapes how AURA speaks rather than being a fact it recites.
 *  - history ([EPISODE]) — injected, but under a header that names it as the past.
 *  - recall-only ([ACTION_LOG], [REFERENCE]) — reachable via recall_memory, never unprompted.
 *
 * Names are the serialized wire format, so [USER]/[FEEDBACK]/[REFERENCE]/[PROJECT] keep their
 * original spelling: renaming them would need `@SerialName` on enum entries (an experimental
 * opt-in) and a mis-parse costs the user their whole memory store. The fix here is the split,
 * not the spelling.
 */
enum class MemoryType {
    /** A durable fact about the user ("has a sister in Coimbatore"). */
    USER,

    /** How the user wants AURA to behave ("keep answers short"). */
    FEEDBACK,

    /** How the user *speaks* — language mix, register, pace. Feeds the persona, not the context. */
    STYLE,

    /** A summary of a past conversation. Injected, but explicitly framed as the past. */
    EPISODE,

    /** The outcome of a phone task. Recall-only: it is a log line, not something AURA knows. */
    ACTION_LOG,

    /** A pointer to something external. Recall-only. */
    REFERENCE,

    /**
     * Legacy catch-all that held facts, episodes and action logs at once. Migrated on read
     * ([migrateLegacy]) and never written again; kept in the enum only so existing JSON parses.
     */
    PROJECT,
}

/** Types that may appear in the unprompted "what I know about you" block. */
private val IDENTITY = setOf(MemoryType.USER, MemoryType.FEEDBACK)

/**
 * The only types a model may write, on either plane. EPISODE and ACTION_LOG are written by the
 * system from real events, so letting a model file into them would let it invent history; PROJECT
 * is legacy and never written again. Shared so the companion lane and the agent lane cannot drift
 * into two different answers to "what may the model save?".
 */
val MODEL_WRITABLE_TYPES = setOf(MemoryType.USER, MemoryType.FEEDBACK, MemoryType.STYLE)

/** Types whose text is a restatement candidate — merging two episodes would fuse two events. */
private val MERGEABLE = setOf(MemoryType.USER, MemoryType.FEEDBACK, MemoryType.STYLE)

@Serializable
data class MemoryEntry(
    val id: String,
    val type: MemoryType,
    val text: String,
    val sensitive: Boolean,
    val createdAtEpochMs: Long,
    /** User-pinned in Settings → Memory: exempt from eviction, always ranked first. */
    val pinned: Boolean = false,
    /** Refreshed when the memory is restated. 0 means "never restated" — fall back to created. */
    val lastConfirmedAtEpochMs: Long = 0L,
    /** How many times this has been stated. Mirrors LearningEntry.successCount. */
    val confirmations: Int = 1,
    /**
     * Lowercase slot this fact occupies ("diet", "employer", "home city"), or "" for the
     * untagged legacy rows. Two claims about the same subject are the SAME memory at different
     * times, so a new one replaces the old instead of stacking beside it — which is how
     * "I'm vegetarian" → "I eat chicken now" resolves. Lexical overlap can never see that
     * contradiction (the two share almost no tokens), so the subject is what carries it.
     */
    val subject: String = "",
    /**
     * When this stopped being true, or 0 while it still is. Only rows in the ended archive carry
     * one: a changed fact is kept as history ("you used to be vegetarian") instead of vanishing.
     */
    val endedAtEpochMs: Long = 0L,
) {
    /** Newest evidence for this memory, whether that is its creation or a later restatement. */
    val freshestAtEpochMs: Long get() = maxOf(createdAtEpochMs, lastConfirmedAtEpochMs)
}

@Serializable
data class Commitment(
    val id: String,
    val text: String,
    val dueAtEpochMs: Long,
    val done: Boolean = false,
    /** A routine: rings at the same time every day until the user dismisses it. */
    val repeatDaily: Boolean = false,
)

/**
 * The companion-plane face of memory (consumed by sub-project #5). Every inbound string is
 * PII-scrubbed on write; entries flagged `sensitive` are excluded from the unprompted session
 * context (V.7: "sensitive memories never surfaced unprompted") but remain reachable via explicit
 * recall. Recall is keyword token-overlap -- the cheap-model side-query (§6) is a documented seam.
 */
interface MemoryService {
    suspend fun save(type: MemoryType, text: String): MemoryEntry
    suspend fun recall(query: String, limit: Int = 5): List<MemoryEntry>
    suspend fun appendEpisode(summary: String)
    suspend fun logAction(taskSummary: String, outcome: String)
    suspend fun dueCommitments(nowEpochMs: Long): List<Commitment>
    suspend fun remind(text: String, dueAtEpochMs: Long): Commitment

    /**
     * M3 — mark commitments delivered. Reminders are one-shot: once the model has
     * surfaced one to the user (via the get_commitments tool) it is done; without
     * this, a due commitment re-surfaces in every session context forever.
     */
    suspend fun completeCommitments(ids: Collection<String>)

    /**
     * A reminder reached the user (it rang, or a conversation relayed it). One-shot reminders are
     * done; a daily routine moves to its next day instead. [completeCommitments] is the user
     * stopping it for good, which is why these are two calls.
     */
    suspend fun markDelivered(ids: Collection<String>) = completeCommitments(ids)
    suspend fun buildSessionContext(nowEpochMs: Long): String

    /**
     * Observed notes about how the user speaks, newest first. These are handed to the persona
     * (not the context block) so AURA mirrors the user's language and register from turn one
     * instead of rediscovering it every session.
     */
    suspend fun styleNotes(limit: Int = 3): List<String>
}

class EncryptedMemoryService(
    private val store: EncryptedJsonStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MemoryService {

    /**
     * Save, or strengthen an existing memory this restates. Restatement updates in place so the
     * id — and therefore the user's pin — survives, and so the summarizer re-extracting the same
     * fact every session stops appending a near-duplicate every session.
     */
    override suspend fun save(type: MemoryType, text: String): MemoryEntry =
        saveResolved(type, text).entry

    /**
     * How a save landed, so a tool result can tell the model what actually happened rather than
     * always claiming "saved" — the model needs to know it *replaced* something to stop re-saving.
     */
    enum class SaveKind { ADDED, REINFORCED, REPLACED }

    data class SaveOutcome(val entry: MemoryEntry, val kind: SaveKind, val previousText: String? = null)

    /**
     * The one write path. Three ways an incoming fact can land on an existing one, in priority
     * order — most explicit first:
     *
     *  1. [supersedesId] — the caller read the memory and named the row it replaces.
     *  2. [subject] — same slot, same type: a new claim about the same thing REPLACES the old one.
     *     This is the contradiction path; it does not need the two texts to look alike.
     *  3. [restates] — the 0.8-overlap duplicate guard, unchanged. Catches the summarizer
     *     re-extracting the same fact every session.
     *
     * A replacement keeps the id (and therefore the user's pin) and resets `confirmations` to 1:
     * the old evidence counted for a claim that is no longer being made.
     */
    suspend fun saveResolved(
        type: MemoryType,
        text: String,
        subject: String = "",
        supersedesId: String? = null,
    ): SaveOutcome {
        val scrubbed = PiiFirewall.scrub(text)
        val sensitive = PiiFirewall.isLikelySensitive(text)
        val slot = subject.trim().lowercase().take(MAX_SUBJECT_LEN)
        val now = clock()
        val all = entries()

        val idx = when {
            supersedesId != null -> all.indexOfFirst { it.id == supersedesId }
            slot.isNotEmpty() && type in MERGEABLE -> all.indexOfFirst { it.type == type && it.subject == slot }
            type in MERGEABLE -> all.indexOfFirst { it.type == type && restates(it.text, scrubbed) }
            else -> -1
        }

        if (idx >= 0) {
            val prev = all[idx]
            val restated = restates(prev.text, scrubbed)
            val updated = prev.copy(
                type = type,
                text = scrubbed,
                sensitive = sensitive,
                // A changed claim starts its evidence count over; a restated one gains weight.
                confirmations = if (restated) prev.confirmations + 1 else 1,
                lastConfirmedAtEpochMs = now,
                subject = slot.ifEmpty { prev.subject },
            )
            store.write(
                ENTRIES,
                all.toMutableList().also { it[idx] = updated },
                serializer<MemoryEntry>(),
            )
            if (!restated) archiveEnded(prev, now)
            return SaveOutcome(
                entry = updated,
                kind = if (restated) SaveKind.REINFORCED else SaveKind.REPLACED,
                previousText = prev.text.takeIf { !restated },
            )
        }

        val entry = MemoryEntry(
            id(), type, scrubbed, sensitive, now,
            lastConfirmedAtEpochMs = now,
            subject = slot,
        )
        store.write(ENTRIES, evict(all + entry), serializer<MemoryEntry>())
        return SaveOutcome(entry, SaveKind.ADDED)
    }

    /**
     * Makes recall understand meaning, not just shared words. Null (tests, no Gemini key, offline)
     * leaves recall exactly as keyword-only as it always was. Set by [memoryService].
     */
    var embedder: Embedder? = null

    override suspend fun recall(query: String, limit: Int): List<MemoryEntry> {
        val terms = tokens(query)
        if (terms.isEmpty()) return emptyList()
        runCatching { semanticRecall(query, terms, limit) }.getOrNull()?.let { return it }
        return entries()
            .map { it to terms.count { t -> it.text.lowercase().contains(t) } }
            .filter { it.second > 0 }
            // Keyword hits first, then the better-established memory of two equal matches.
            .sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.confirmations })
            .take(limit)
            .map { it.first }
    }

    /** Null when embeddings are unavailable, so the caller falls back to keywords. */
    private suspend fun semanticRecall(query: String, terms: List<String>, limit: Int): List<MemoryEntry>? {
        val e = embedder ?: return null
        val all = entries()
        val vectors = refreshVectors(all) ?: return null
        val q = e.embed(listOf(query), isQuery = true)?.firstOrNull() ?: return null
        return SemanticRanker.rank(q, all, vectors, { m -> terms.count { t -> m.text.lowercase().contains(t) } }, limit)
    }

    /**
     * Embeds whatever is new or edited since the last pass. The nightly worker calls this so a
     * voice-lane recall normally pays only for its query, not a backfill round-trip mid-sentence.
     * Null when embeddings are unavailable.
     */
    suspend fun refreshVectors(all: List<MemoryEntry> = entries()): Map<String, StoredVector>? {
        val e = embedder ?: return null
        val known = store.read(VECTORS, serializer<StoredVector>()).associateBy { it.id }
        val stale = SemanticRanker.stale(all, known)
        if (stale.isEmpty()) return known
        val fresh = e.embed(stale.map { it.text }, isQuery = false) ?: return null
        val live = all.mapTo(HashSet()) { it.id }
        val merged = known.filterKeys { it in live } +
            stale.zip(fresh).associate { (m, v) -> m.id to StoredVector(m.id, SemanticRanker.textHash(m), VectorCodec.encode(v)) }
        store.write(VECTORS, merged.values.toList(), serializer<StoredVector>())
        return merged
    }

    override suspend fun appendEpisode(summary: String) { save(MemoryType.EPISODE, summary) }

    override suspend fun logAction(taskSummary: String, outcome: String) {
        save(MemoryType.ACTION_LOG, "$taskSummary → $outcome")
    }

    override suspend fun styleNotes(limit: Int): List<String> =
        entries().filter { it.type == MemoryType.STYLE }
            .sortedByDescending { it.freshestAtEpochMs }
            .take(limit)
            .map { it.text }

    override suspend fun dueCommitments(nowEpochMs: Long): List<Commitment> =
        commitments().filter { !it.done && it.dueAtEpochMs <= nowEpochMs }

    /**
     * Told about every new reminder once it is stored, so something can make it ring. A stored
     * reminder used to wait for the user's next conversation to be mentioned at all. Set by
     * [memoryService]; tests and read-only callers leave it a no-op.
     */
    var onRemind: (Commitment) -> Unit = {}

    override suspend fun remind(text: String, dueAtEpochMs: Long): Commitment = remind(text, dueAtEpochMs, repeatDaily = false)

    suspend fun remind(text: String, dueAtEpochMs: Long, repeatDaily: Boolean): Commitment {
        val c = Commitment(id(), PiiFirewall.scrub(text), dueAtEpochMs, repeatDaily = repeatDaily)
        store.write(COMMITMENTS, (commitments() + c).takeLast(MAX_ENTRIES), serializer<Commitment>())
        runCatching { onRemind(c) }
        return c
    }

    /** Reminders not yet delivered, for re-arming alarms after a reboot. */
    fun pendingCommitments(): List<Commitment> = commitments().filterNot { it.done }

    override suspend fun completeCommitments(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        store.write(
            COMMITMENTS,
            commitments().map { if (it.id in idSet) it.copy(done = true) else it },
            serializer<Commitment>(),
        )
    }

    override suspend fun markDelivered(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        val now = clock()
        val updated = commitments().map { c ->
            when {
                c.id !in idSet -> c
                c.repeatDaily -> c.copy(dueAtEpochMs = HistoryMemory.nextDaily(c.dueAtEpochMs, now))
                else -> c.copy(done = true)
            }
        }
        store.write(COMMITMENTS, updated, serializer<Commitment>())
        updated.filter { it.id in idSet && it.repeatDaily }.forEach { runCatching { onRemind(it) } }
    }

    /**
     * Two clearly-separated blocks. What the model knows about the user is ranked (pinned first,
     * then best-established, then freshest) rather than merely recent, so a chatty session can no
     * longer flush durable facts out of the window. Past conversations get their own header that
     * says what they are — the previous single "What I remember" list is what let the model report
     * an old episode as a thing it had just seen.
     */
    override suspend fun buildSessionContext(nowEpochMs: Long): String {
        val visible = entries().filterNot { it.sensitive }
        val identity = visible.filter { it.type in IDENTITY }
            .sortedWith(
                compareByDescending<MemoryEntry> { it.pinned }
                    .thenByDescending { it.confirmations }
                    .thenByDescending { it.freshestAtEpochMs },
            )
            .take(MAX_IDENTITY)
        val episodes = visible.filter { it.type == MemoryType.EPISODE }
            .sortedByDescending { it.freshestAtEpochMs }
            .take(MAX_EPISODES)
        val due = dueCommitments(nowEpochMs)
        if (identity.isEmpty() && episodes.isEmpty() && due.isEmpty()) return ""

        return buildString {
            if (identity.isNotEmpty()) {
                append("# What I know about them\n")
                identity.forEach { append("• ${it.text}\n") }
            }
            if (episodes.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("# Earlier conversations (summaries of past chats — NOT things happening now; ")
                append("never report these as something you just did or just saw)\n")
                episodes.forEach { append("• ${it.text} (${ageOf(nowEpochMs, it.freshestAtEpochMs)})\n") }
            }
            if (due.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("# Reminders due\n")
                due.forEach { append("• ${it.text}\n") }
                // M3 — get_commitments marks what it returns as delivered; telling the model
                // to call it after relaying is what stops a reminder re-surfacing forever.
                append("(Tell the user, then call get_commitments to mark these delivered.)\n")
            }
        }.trimEnd()
    }

    fun allEntries(): List<MemoryEntry> = entries()

    /**
     * Swap a week of day-level diary rows for one summary row dated at the week's start. Written in
     * one store write, so a crash cannot leave the week both summarized and still present, or gone.
     */
    suspend fun replaceWithRollup(ids: Set<String>, heading: String, summary: String, weekStartEpochMs: Long) {
        // Only the model-written part is scrubbed: the scrubber reads a year in the heading as a code.
        val rollup = MemoryEntry(
            id(), MemoryType.EPISODE, "$heading: ${PiiFirewall.scrub(summary)}", PiiFirewall.isLikelySensitive(summary), weekStartEpochMs,
            lastConfirmedAtEpochMs = weekStartEpochMs,
            subject = MemoryMaintenance.ROLLUP_SUBJECT,
        )
        store.write(ENTRIES, evict(entries().filterNot { it.id in ids } + rollup), serializer<MemoryEntry>())
    }

    /** Facts that used to be true, newest first. Never injected; reachable through recall. */
    fun endedEntries(): List<MemoryEntry> =
        store.read(ENDED, serializer<MemoryEntry>()).sortedByDescending { it.endedAtEpochMs }

    /**
     * The user said something is no longer true and there is nothing to replace it with. Moves the
     * fact to the ended archive. A pinned fact is refused — only the user unpins.
     */
    suspend fun endFact(id: String): Boolean {
        val all = entries()
        val entry = all.firstOrNull { it.id == id } ?: return false
        if (entry.pinned) return false
        store.write(ENTRIES, all.filterNot { it.id == id }, serializer<MemoryEntry>())
        archiveEnded(entry, clock())
        return true
    }

    private fun archiveEnded(entry: MemoryEntry, now: Long) {
        if (entry.type !in IDENTITY) return
        val ended = entry.copy(id = id(), pinned = false, endedAtEpochMs = now)
        store.write(
            ENDED,
            (store.read(ENDED, serializer<MemoryEntry>()) + ended).takeLast(MAX_ENDED),
            serializer<MemoryEntry>(),
        )
    }

    /** All commitments, pending first — the Settings → Memory inspector's view (M3/M13). */
    fun allCommitments(): List<Commitment> =
        commitments().sortedWith(compareBy({ it.done }, { it.dueAtEpochMs }))

    /** Settings → Memory: drop one memory the user disagrees with, without clearing everything. */
    suspend fun delete(id: String) {
        store.write(ENTRIES, entries().filterNot { it.id == id }, serializer<MemoryEntry>())
    }

    /** Settings → Memory: pin keeps a memory out of eviction and first in the context block. */
    suspend fun setPinned(id: String, pinned: Boolean) {
        store.write(
            ENTRIES,
            entries().map { if (it.id == id) it.copy(pinned = pinned) else it },
            serializer<MemoryEntry>(),
        )
    }

    fun clear() = store.clearAll()

    private fun entries() = migrateLegacy(store.read(ENTRIES, serializer<MemoryEntry>()))

    /**
     * Reclassify pre-split [MemoryType.PROJECT] rows. `logAction` wrote "task → outcome", so the
     * arrow is the one reliable discriminator between an action log and an episode summary; a
     * misfiled row is harmless either way (recall-only vs. an explicitly-past bullet). Pure and
     * idempotent — the next write persists it, so no migration step has to run at startup.
     */
    private fun migrateLegacy(raw: List<MemoryEntry>): List<MemoryEntry> {
        if (raw.none { it.type == MemoryType.PROJECT }) return raw
        return raw.map {
            if (it.type != MemoryType.PROJECT) {
                it
            } else {
                it.copy(type = if (it.text.contains(" → ")) MemoryType.ACTION_LOG else MemoryType.EPISODE)
            }
        }
    }

    /**
     * Cap the store per tier (newest kept), never evicting something the user pinned. Stored order
     * is preserved — it is chronological, and recall ties fall back to it.
     */
    private fun evict(all: List<MemoryEntry>): List<MemoryEntry> {
        val keep = MemoryConsolidator.capByTier(all.asReversed(), MAX_ENTRIES).toSet()
        return if (keep.size == all.size) all else all.filter { it in keep }
    }

    /**
     * Is [candidate] the same memory as [existing], restated? Deliberately conservative: a high
     * overlap threshold merges "the summarizer extracted this same fact again" (the real duplicate
     * source, since in-session dedup does not survive a restart) while leaving genuinely different
     * facts alone. "sister lives in Coimbatore" vs "brother lives in Coimbatore" is 0.67 and stays
     * two memories — losing a fact silently is far worse than showing a near-duplicate the user
     * can delete in Settings → Memory.
     */
    private fun restates(existing: String, candidate: String): Boolean {
        if (existing.equals(candidate, ignoreCase = true)) return true
        val a = tokens(existing).toSet()
        val b = tokens(candidate).toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size) >= RESTATE_THRESHOLD
    }

    private fun ageOf(nowMs: Long, thenMs: Long): String {
        val days = ((nowMs - thenMs) / DAY_MS).coerceAtLeast(0)
        return if (days == 0L) "today" else "${days}d ago"
    }

    private fun commitments() = store.read(COMMITMENTS, serializer<Commitment>())
    private fun tokens(s: String) = s.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
    private fun id(): String = "${clock()}-${(0..9999).random()}"

    private companion object {
        const val ENTRIES = "entries"
        const val COMMITMENTS = "commitments"
        const val VECTORS = "vectors"
        const val ENDED = "ended"
        const val MAX_ENDED = 100
        const val MAX_ENTRIES = 200
        const val MAX_IDENTITY = 10
        const val MAX_EPISODES = 3
        const val RESTATE_THRESHOLD = 0.8
        const val MAX_SUBJECT_LEN = 32
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
