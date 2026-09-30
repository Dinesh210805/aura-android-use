package com.aura.aura_ui.agent.memory

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The agent lane's diary: what gets written after a run, and how "what did I do yesterday" finds it.
 *
 * Before this, EPISODE and ACTION_LOG were only ever written by the Live plane — which is off by
 * default — so on most installs AURA had no past at all. Pure, unit-tested in `HistoryMemoryTest`.
 */
object HistoryMemory {
    /** Types that are records of moments rather than facts about the user. */
    val HISTORY_TYPES = setOf(MemoryType.EPISODE, MemoryType.ACTION_LOG)

    /**
     * The (task, outcome) pair for one finished run, or null when there is nothing worth a line.
     *
     * `completed` only means the loop ended without throwing, not that the goal was met — so the
     * outcome is the agent's own closing sentence, which says which it was, rather than "done".
     */
    fun actionLog(goal: String, reply: String?, endReason: String): Pair<String, String>? {
        val task = goal.trim().replace(WHITESPACE, " ").take(MAX_TASK_CHARS)
        if (task.isEmpty()) return null
        val outcome = when (endReason) {
            "completed" -> reply?.let(::firstSentence)?.takeIf { it.isNotEmpty() } ?: "finished"
            "cancelled" -> "cancelled"
            "paused" -> "paused before finishing"
            "budget" -> "stopped: ran out of budget"
            "quota" -> "stopped: provider quota exhausted"
            else -> "failed"
        }
        return task to outcome
    }

    /**
     * The time span a recall query names, or null when it names none. Keyword recall cannot answer
     * "what did I do yesterday": no diary line contains the word "yesterday".
     */
    fun windowOf(query: String, nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LongRange? {
        val q = query.lowercase()
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        fun start(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return when {
            YESTERDAY.any { it in q } -> start(today.minusDays(1)) until start(today)
            TODAY.any { it in q } -> start(today)..nowEpochMs
            WEEK.any { it in q } -> start(today.minusDays(7))..nowEpochMs
            RECENT.any { it in q } -> start(today.minusDays(2))..nowEpochMs
            else -> null
        }
    }

    /**
     * When a reminder is due, from time as people say it. `in_minutes` wins; otherwise `at` (HH:mm)
     * on today + [daysFromNow], rolled to tomorrow when today's slot has already passed and no day
     * was named. Null for anything unusable — a reminder at a guessed time is worse than a question.
     */
    fun dueTime(nowEpochMs: Long, inMinutes: Int?, at: String?, daysFromNow: Int, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (inMinutes != null) return if (inMinutes > 0) nowEpochMs + inMinutes * 60_000L else null
        val time = at?.let { runCatching { java.time.LocalTime.parse(it.trim()) }.getOrNull() } ?: return null
        if (daysFromNow < 0) return null
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        var due = now.toLocalDate().plusDays(daysFromNow.toLong()).atTime(time).atZone(zone)
        if (daysFromNow == 0 && !due.isAfter(now)) due = due.plusDays(1)
        return due.toInstant().toEpochMilli()
    }

    /** The next time a daily routine is due after [nowEpochMs], keeping its wall-clock time across DST. */
    fun nextDaily(dueAtEpochMs: Long, nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        var due = Instant.ofEpochMilli(dueAtEpochMs).atZone(zone)
        while (due.toInstant().toEpochMilli() <= nowEpochMs) due = due.plusDays(1)
        return due.toInstant().toEpochMilli()
    }

    /** "Sat 12 Sep, 21:04" — a history line without its date reads as something happening now. */
    fun stamp(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        STAMP.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private fun firstSentence(text: String): String {
        val flat = text.trim().replace(WHITESPACE, " ")
        val end = SENTENCE_END.find(flat)?.range?.first?.plus(1) ?: flat.length
        return flat.take(minOf(end, MAX_OUTCOME_CHARS)).trim()
    }

    private val WHITESPACE = Regex("\\s+")
    private val SENTENCE_END = Regex("[.!?](\\s|$)")
    private val STAMP = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
    private val YESTERDAY = listOf("yesterday", "last night")
    private val TODAY = listOf("today", "this morning", "this afternoon", "this evening", "tonight")
    private val WEEK = listOf("this week", "last week", "past week", "few days")
    private val RECENT = listOf("recent", "lately", "earlier")
    private const val MAX_TASK_CHARS = 160
    private const val MAX_OUTCOME_CHARS = 160
}
