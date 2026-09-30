package com.aura.aura_ui.agent.memory

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class MemoryMaintenanceTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, utc).toInstant().toEpochMilli()

    /** Sun 13 Sep 2026. This week starts Mon 7 Sep. */
    private val now = at(2026, 9, 13)

    private fun row(id: String, type: MemoryType, text: String, t: Long, subject: String = "") =
        MemoryEntry(id, type, text, false, t, subject = subject)

    @Test fun `only weeks that ended at least a week ago are rolled, whole`() {
        val rows = listOf(
            row("a", MemoryType.ACTION_LOG, "ordered biryani", at(2026, 8, 25)), // week of 24 Aug
            row("b", MemoryType.EPISODE, "planned the benchmark", at(2026, 8, 27)), // week of 24 Aug
            row("c", MemoryType.EPISODE, "worked on memory", at(2026, 9, 2)), // week of 31 Aug — ended 6 Sep, too recent
            row("d", MemoryType.USER, "is vegetarian", at(2026, 8, 25)), // not history
            row("e", MemoryType.EPISODE, "Week of 17 Aug: …", at(2026, 8, 17), MemoryMaintenance.ROLLUP_SUBJECT), // already a rollup
        )
        val weeks = MemoryMaintenance.weeksToRoll(rows, now, utc)
        assertEquals(listOf(LocalDate.of(2026, 8, 24)), weeks.keys.toList())
        assertEquals(setOf("a", "b"), weeks.values.single().map { it.id }.toSet())
    }

    @Test fun `a rolled week replaces its day lines with one dated summary`() = runTest {
        var clock = at(2026, 8, 25)
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "maint_1")) { clock }
        memory.logAction("order biryani", "Ordered.")
        clock = at(2026, 8, 27)
        memory.appendEpisode("You planned the AndroidWorld benchmark.")
        memory.save(MemoryType.USER, "is vegetarian")

        val m = MemoryMaintenance(memory, { "You planned the benchmark and ordered biryani." }, FactReconciler(memory) { null }, { now }, utc)
        assertEquals(1, m.rollUpOldWeeks())

        val history = memory.allEntries().filter { it.type in HistoryMemory.HISTORY_TYPES }
        val rollup = history.single()
        assertTrue(rollup.text.startsWith("Week of 24 Aug 2026:"))
        assertEquals(MemoryMaintenance.ROLLUP_SUBJECT, rollup.subject)
        assertEquals(at(2026, 8, 24, 0), rollup.createdAtEpochMs)
        assertTrue("facts are untouched", memory.allEntries().any { it.text == "is vegetarian" })
    }

    @Test fun `a failed summary deletes nothing`() = runTest {
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "maint_2")) { at(2026, 8, 25) }
        memory.logAction("order biryani", "Ordered.")
        val m = MemoryMaintenance(memory, { null }, FactReconciler(memory) { null }, { now }, utc)
        assertEquals(0, m.rollUpOldWeeks())
        assertEquals("order biryani → Ordered.", memory.allEntries().single().text)
    }

    @Test fun `rollups outlive the 30-day diary expiry`() {
        val old = now - 90L * 24 * 60 * 60 * 1000
        val kept = MemoryConsolidator.consolidateEntries(
            listOf(
                row("r", MemoryType.EPISODE, "Week of June", old, MemoryMaintenance.ROLLUP_SUBJECT),
                row("d", MemoryType.EPISODE, "a June day", old),
            ),
            nowEpochMs = now, ttlMs = 30L * 24 * 60 * 60 * 1000, cap = 200,
        )
        assertEquals(listOf("Week of June"), kept.map { it.text })
    }

    @Test fun `reflection turns patterns into facts through the reconciler, and waits for enough diary`() = runTest {
        var clock = now - 3L * 24 * 60 * 60 * 1000
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "maint_3")) { clock }
        val reply = """{"insights": ["works late at night on AURA"]}"""
        val m = MemoryMaintenance(memory, { reply }, FactReconciler(memory) { null }, { now }, utc)

        memory.appendEpisode("You debugged AURA until 2am.")
        assertEquals("one day is not a pattern", 0, m.reflect())

        clock += 24 * 60 * 60 * 1000
        memory.appendEpisode("You shipped an AURA build late at night.")
        clock += 24 * 60 * 60 * 1000
        memory.appendEpisode("You fixed memory bugs past midnight.")
        assertEquals(1, m.reflect())
        assertTrue(memory.allEntries().any { it.type == MemoryType.USER && it.text == "works late at night on AURA" })
    }

    @Test fun `insight parsing tolerates fences and junk`() {
        assertEquals(listOf("a", "b"), MemoryMaintenance.parseInsights("```json\n{\"insights\":[\"a\",\" b \",\"\"]}\n```"))
        assertTrue(MemoryMaintenance.parseInsights("nope").isEmpty())
    }
}
