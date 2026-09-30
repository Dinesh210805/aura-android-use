package com.aura.aura_ui.agent.memory

import android.util.Log
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The nightly pass that keeps memory useful as it grows, run by [MemoryConsolidationWorker]:
 *
 *  1. **Weekly rollups.** Day-level diary lines older than two weeks become one summary per week,
 *     so a month of use still fits the history cap — and "what was I doing in August" still has
 *     an answer after the daily lines would have expired.
 *  2. **Reflection.** Reads the past week's diary against what is already known and writes a few
 *     insights ("works late before releases") through the [FactReconciler], so a pattern that is
 *     already known is not saved twice.
 *
 * Every model failure skips that piece until tomorrow; nothing is deleted unless its summary landed.
 */
class MemoryMaintenance(
    private val memory: EncryptedMemoryService,
    private val llm: suspend (String) -> String?,
    private val reconciler: FactReconciler,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Returns how many weeks were rolled up. */
    suspend fun rollUpOldWeeks(): Int {
        var rolled = 0
        weeksToRoll(memory.allEntries(), clock(), zone).entries.take(MAX_WEEKS_PER_PASS).forEach { (weekStart, rows) ->
            val summary = llm(rollupPrompt(weekStart, rows))?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("{") }
                ?: return@forEach
            val startMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
            memory.replaceWithRollup(rows.mapTo(HashSet()) { it.id }, "Week of ${weekStart.format(WEEK)}", summary.take(MAX_ROLLUP_CHARS), startMs)
            rolled++
        }
        return rolled
    }

    /** Returns how many insights were offered to the reconciler. */
    suspend fun reflect(): Int {
        val since = clock() - REFLECT_WINDOW_MS
        val recent = memory.allEntries().filter { it.type in HistoryMemory.HISTORY_TYPES && it.createdAtEpochMs >= since }
        if (recent.count { it.type == MemoryType.EPISODE } < MIN_EPISODES_TO_REFLECT) return 0
        val known = memory.allEntries().filter { it.type == MemoryType.USER && !it.sensitive }
        val insights = llm(reflectPrompt(recent, known))?.let(::parseInsights).orEmpty()
        insights.take(MAX_INSIGHTS).forEach { reconciler.reconcile(it) }
        Log.d(TAG, "reflection offered ${insights.size} insight(s)")
        return insights.size
    }

    companion object {
        private const val TAG = "MemoryMaintenance"
        const val ROLLUP_SUBJECT = "rollup:week"
        private const val MAX_WEEKS_PER_PASS = 4
        private const val MAX_ROLLUP_CHARS = 600
        private const val MAX_INSIGHTS = 3
        private const val MIN_EPISODES_TO_REFLECT = 3
        private const val REFLECT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        private val WEEK = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)

        /**
         * Day-level history grouped by week (Monday start), for weeks that ended at least a week ago
         * — so a week is rolled once, whole, never half now and half later. Oldest week first.
         */
        fun weeksToRoll(entries: List<MemoryEntry>, nowEpochMs: Long, zone: ZoneId): Map<LocalDate, List<MemoryEntry>> {
            val thisWeek = weekStartOf(nowEpochMs, zone)
            return entries
                .filter { it.type in HistoryMemory.HISTORY_TYPES && !it.pinned && it.subject != ROLLUP_SUBJECT }
                .groupBy { weekStartOf(it.createdAtEpochMs, zone) }
                .filterKeys { !it.plusWeeks(2).isAfter(thisWeek) }
                .toSortedMap()
        }

        private fun weekStartOf(epochMs: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        fun rollupPrompt(weekStart: LocalDate, rows: List<MemoryEntry>): String = buildString {
            appendLine("Below is one week of an assistant's diary about its user (week of ${weekStart.format(WEEK)}).")
            appendLine("Write ONE plain-text paragraph, at most 80 words, second person (\"You...\"), keeping what")
            appendLine("mattered: projects, people, plans, notable tasks. Drop routine one-offs. No JSON, no heading.")
            appendLine()
            rows.sortedBy { it.createdAtEpochMs }.forEach { appendLine("- ${it.text}") }
        }

        fun reflectPrompt(recent: List<MemoryEntry>, known: List<MemoryEntry>): String = buildString {
            appendLine("You are reflecting on a user's past week to understand them better.")
            appendLine("Already known about them:")
            if (known.isEmpty()) appendLine("- (nothing yet)") else known.forEach { appendLine("- ${it.text}") }
            appendLine("This week's diary:")
            recent.sortedBy { it.createdAtEpochMs }.forEach { appendLine("- ${it.text}") }
            appendLine()
            appendLine("""Reply ONLY with JSON: {"insights": string[]}. 0-3 short, durable insights about their""")
            appendLine("habits, preferences, routines or ongoing projects that are NOT already known and that the diary")
            appendLine("shows on at least two separate occasions. Nothing sensitive (health, money, passwords). Empty")
            append("array if nothing clear.")
        }

        fun parseInsights(raw: String): List<String> {
            val body = raw.substringAfter("```json", raw).substringBefore("```")
            val start = body.indexOf('{')
            val end = body.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            return runCatching {
                val arr = JSONObject(body.substring(start, end + 1)).optJSONArray("insights") ?: return emptyList()
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            }.getOrDefault(emptyList())
        }
    }
}
