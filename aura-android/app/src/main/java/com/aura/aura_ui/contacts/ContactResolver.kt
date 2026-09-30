package com.aura.aura_ui.contacts

import android.util.Log
import com.aura.aura_ui.data.database.dao.AuraContactDao
import com.aura.aura_ui.data.database.entities.AuraContactEntity
import org.apache.commons.codec.language.DoubleMetaphone
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Five-stage fuzzy contact resolver targeting < 50 ms for ≤ 5,000 contacts.
 *
 * Pipeline (highest score wins per candidate):
 *   Stage 1 — Exact match on normalized name or alias          → 1.00
 *   Stage 2 — Prefix match (name token starts with query)      → 0.85
 *   Stage 3 — Levenshtein distance ≤ 2 on best token           → 0.75–0.85
 *   Stage 4 — Double Metaphone phonetic match                  → 0.70–0.78
 *   Stage 5 — Token sort ratio (handles reversed name order)   → 0.60–0.75
 *
 * Thresholds:
 *   score > 0.92, single candidate → AUTO_RESOLVE
 *   score > 0.70, top candidate    → DISAMBIGUATE (up to 4 candidates ≥ 0.60)
 *   otherwise                      → MANUAL_ENTRY
 */
@Singleton
class ContactResolver @Inject constructor(
    private val contactDao: AuraContactDao
) {

    companion object {
        private const val TAG = "ContactResolver"
        private val dm = DoubleMetaphone()

        // Score thresholds
        private const val THRESHOLD_AUTO = 0.92f
        private const val THRESHOLD_DISAMBIGUATE = 0.70f
        private const val THRESHOLD_CANDIDATE = 0.60f

        // Stage scores
        private const val SCORE_EXACT = 1.00f
        private const val SCORE_PREFIX = 0.85f
        private const val SCORE_LEV_BASE = 0.85f   // at distance 0
        private const val SCORE_LEV_STEP = 0.05f   // deducted per edit
        private const val SCORE_PHONETIC_PRIMARY = 0.78f
        private const val SCORE_PHONETIC_ALT = 0.70f
        private const val SCORE_TOKEN_MAX = 0.75f
        private const val SCORE_TOKEN_MIN = 0.60f
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolve [sttInput] against the full contacts DB.
     * Must be called from a coroutine (suspends for DB query).
     */
    suspend fun resolve(sttInput: String): ResolveResult {
        val all = contactDao.getAllContacts()
        return resolveFromList(sttInput, all)
    }

    /**
     * Pure scoring entry point — exposed for unit tests and callers that already
     * have the contact list in memory.
     */
    fun resolveFromList(
        sttInput: String,
        candidates: List<AuraContactEntity>
    ): ResolveResult {
        val query = normalize(sttInput)
        if (query.isBlank()) return ResolveResult.ManualEntry

        val scored = candidates
            .mapNotNull { score(query, it) }
            .sortedByDescending { it.score }
            .take(4)

        Log.d(TAG, "resolve('$sttInput') → ${scored.size} candidates above threshold")
        scored.forEach { Log.d(TAG, "  • ${it.displayName} %.2f [${it.matchStage}]".format(it.score)) }

        if (scored.isEmpty()) return ResolveResult.ManualEntry

        val top = scored.first()
        return when {
            top.score > THRESHOLD_AUTO && scored.size == 1 -> ResolveResult.AutoResolve(top)
            top.score > THRESHOLD_DISAMBIGUATE -> ResolveResult.Disambiguate(scored)
            else -> ResolveResult.ManualEntry
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoring
    // ─────────────────────────────────────────────────────────────────────────

    private fun score(query: String, entity: AuraContactEntity): ContactMatch? {
        val aliases = parseJsonArray(entity.aliases)
        val tokens = tokenize(entity.displayName)
        val primaryPhone = parseJsonArray(entity.phoneNumbers).firstOrNull() ?: ""

        var bestScore = 0f
        var bestStage = MatchStage.EXACT

        // Stage 1 — Exact
        val exactScore = scoreExact(query, entity.displayName, aliases)
        if (exactScore > bestScore) { bestScore = exactScore; bestStage = MatchStage.EXACT }

        // Stage 2 — Prefix
        val prefixScore = scorePrefix(query, tokens, aliases)
        if (prefixScore > bestScore) { bestScore = prefixScore; bestStage = MatchStage.PREFIX }

        // Stage 3 — Levenshtein
        val levScore = scoreLevenshtein(query, tokens, aliases)
        if (levScore > bestScore) { bestScore = levScore; bestStage = MatchStage.LEVENSHTEIN }

        // Stage 4 — Phonetic
        val phoneticScore = scorePhonetic(query, tokens, entity)
        if (phoneticScore > bestScore) { bestScore = phoneticScore; bestStage = MatchStage.PHONETIC }

        // Stage 5 — Token sort ratio
        val tokenScore = scoreTokenSort(query, entity.displayName)
        if (tokenScore > bestScore) { bestScore = tokenScore; bestStage = MatchStage.TOKEN_SORT }

        if (bestScore < THRESHOLD_CANDIDATE) return null

        return ContactMatch(
            contactId = entity.contactId,
            displayName = entity.displayName,
            phoneNumber = primaryPhone,
            photoUri = entity.photoUri,
            score = bestScore,
            matchStage = bestStage
        )
    }

    // Stage 1
    private fun scoreExact(query: String, displayName: String, aliases: List<String>): Float {
        if (normalize(displayName) == query) return SCORE_EXACT
        for (alias in aliases) {
            if (normalize(alias) == query) return SCORE_EXACT
        }
        // Exact match against individual tokens (first name match)
        val tokens = tokenize(displayName)
        if (tokens.any { it == query }) return 0.97f
        return 0f
    }

    // Stage 2
    private fun scorePrefix(query: String, tokens: List<String>, aliases: List<String>): Float {
        // Full name or alias starts with query
        for (token in tokens) {
            if (token.startsWith(query) || query.startsWith(token)) return SCORE_PREFIX
        }
        for (alias in aliases) {
            val an = normalize(alias)
            if (an.startsWith(query) || query.startsWith(an)) return SCORE_PREFIX
        }
        // Partial prefix: first 3+ characters match
        if (query.length >= 3) {
            val prefix3 = query.take(3)
            for (token in tokens) {
                if (token.startsWith(prefix3)) return SCORE_PREFIX * (prefix3.length.toFloat() / query.length)
            }
        }
        return 0f
    }

    // Stage 3
    private fun scoreLevenshtein(query: String, tokens: List<String>, aliases: List<String>): Float {
        var best = Int.MAX_VALUE
        for (token in tokens) {
            val d = levenshtein(query, token)
            if (d < best) best = d
        }
        for (alias in aliases) {
            val d = levenshtein(query, normalize(alias))
            if (d < best) best = d
        }
        if (best > 2) return 0f
        return SCORE_LEV_BASE - best * SCORE_LEV_STEP
    }

    // Stage 4
    private fun scorePhonetic(query: String, tokens: List<String>, entity: AuraContactEntity): Float {
        val qPrimary = dm.doubleMetaphone(query) ?: ""
        val qAlt = dm.doubleMetaphone(query, true) ?: ""

        // Fast path: compare against pre-computed DB keys
        if (entity.phoneticKey.isNotBlank()) {
            if (qPrimary == entity.phoneticKey || qAlt == entity.phoneticKey) return SCORE_PHONETIC_PRIMARY
            if (qPrimary == entity.phoneticKeyAlt || qAlt == entity.phoneticKeyAlt) return SCORE_PHONETIC_ALT
        }

        // Slow path: compute on-the-fly for each token
        for (token in tokens) {
            val tPrimary = dm.doubleMetaphone(token) ?: ""
            val tAlt = dm.doubleMetaphone(token, true) ?: ""
            if (qPrimary == tPrimary && qPrimary.isNotEmpty()) return SCORE_PHONETIC_PRIMARY
            if (qPrimary == tAlt || qAlt == tPrimary) return SCORE_PHONETIC_ALT
            if (qAlt == tAlt && qAlt.isNotEmpty()) return SCORE_PHONETIC_ALT
        }
        return 0f
    }

    // Stage 5
    private fun scoreTokenSort(query: String, displayName: String): Float {
        // Sort tokens of both strings, join, then compute Levenshtein ratio
        val querySort = tokenize(query).sorted().joinToString(" ")
        val nameSort = tokenize(displayName).sorted().joinToString(" ")
        val dist = levenshtein(querySort, nameSort)
        val maxLen = max(querySort.length, nameSort.length).coerceAtLeast(1)
        val similarity = 1f - dist.toFloat() / maxLen
        if (similarity <= 0f) return 0f
        // Map [0, 1] → [SCORE_TOKEN_MIN, SCORE_TOKEN_MAX]
        return SCORE_TOKEN_MIN + similarity * (SCORE_TOKEN_MAX - SCORE_TOKEN_MIN)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** Lowercase + collapse whitespace. Does not strip diacritics — DM handles that. */
    private fun normalize(s: String): String =
        s.lowercase().trim().replace(Regex("\\s+"), " ")

    /** Split on whitespace, normalize each token, filter blanks. */
    private fun tokenize(s: String): List<String> =
        normalize(s).split(" ").filter { it.isNotBlank() }

    private fun parseJsonArray(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }

    /**
     * Classic DP Levenshtein distance, O(m*n) time, O(min(m,n)) space.
     * Fast enough for name-length strings (≤ 30 chars).
     */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a

        var prev = IntArray(shorter.length + 1) { it }
        var curr = IntArray(shorter.length + 1)

        for (i in 1..longer.length) {
            curr[0] = i
            for (j in 1..shorter.length) {
                val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
                curr[j] = min(
                    min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[shorter.length]
    }

    /** Compute Double Metaphone primary code for a name token. */
    fun computePhoneticKey(token: String): String = dm.doubleMetaphone(token) ?: ""
    fun computePhoneticKeyAlt(token: String): String = dm.doubleMetaphone(token, true) ?: ""
}
