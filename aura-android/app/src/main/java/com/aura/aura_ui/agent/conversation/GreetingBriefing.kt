package com.aura.aura_ui.agent.conversation

import com.aura.aura_ui.agent.memory.Commitment
import com.aura.aura_ui.agent.memory.HistoryMemory
import com.aura.aura_ui.agent.memory.MemoryEntry
import com.aura.aura_ui.agent.memory.MemoryType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What AURA may raise when a Live conversation opens: "Morning. Two messages from Amma, and you
 * said you'd send the results today." Rendered into the Live system instruction only — the agent
 * lane never sees it, so it cannot move an eval number.
 *
 * The first conversation of the day gets yesterday's tasks and the day's reminders; every later
 * one gets only what is happening now. Null when there is nothing to say, which is the common case
 * and earns a plain hello. Pure, unit-tested in `GreetingBriefingTest`.
 */
object GreetingBriefing {
    const val HEADING = "# Worth mentioning when you open"

    fun build(
        notable: List<String>,
        history: List<MemoryEntry>,
        pending: List<Commitment>,
        nowEpochMs: Long,
        firstToday: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterday = if (!firstToday) emptyList() else {
            val span = HistoryMemory.windowOf("yesterday", nowEpochMs, zone) ?: LongRange.EMPTY
            history.filter { it.type == MemoryType.ACTION_LOG && !it.sensitive && it.createdAtEpochMs in span }
                .sortedByDescending { it.createdAtEpochMs }
                .take(MAX_ITEMS)
        }
        val laterToday = pending.filter { it.dueAtEpochMs in (nowEpochMs + 1) until endOfDay }
            .sortedBy { it.dueAtEpochMs }
            .take(MAX_ITEMS)
        if (notable.isEmpty() && yesterday.isEmpty() && laterToday.isEmpty()) return null

        return buildString {
            append(HEADING).append('\n')
            append("It is ").append(partOfDay(now.hour)).append(". ")
            append(if (firstToday) "This is the first time they have opened you today.\n" else "They already spoke with you earlier today.\n")
            if (notable.isNotEmpty()) append("Right now: ").append(notable.joinToString("; ")).append(".\n")
            if (yesterday.isNotEmpty()) {
                append("Yesterday's tasks (already over, never report them as happening now): ")
                append(yesterday.joinToString("; ") { it.text }).append(".\n")
            }
            if (laterToday.isNotEmpty()) {
                append("Reminders later today: ")
                append(laterToday.joinToString("; ") { "${it.text} at ${TIME.format(Instant.ofEpochMilli(it.dueAtEpochMs).atZone(zone))}" })
                append(".\n")
            }
        }.trimEnd()
    }

    private fun partOfDay(hour: Int) = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    private const val MAX_ITEMS = 3
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
}
