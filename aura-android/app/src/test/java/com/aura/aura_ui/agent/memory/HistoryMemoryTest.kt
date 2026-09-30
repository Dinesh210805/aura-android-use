package com.aura.aura_ui.agent.memory

import android.content.Context
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class HistoryMemoryTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val utc = ZoneId.of("UTC")

    /** Sun 13 Sep 2026, 10:00 UTC. */
    private val now = ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, utc).toInstant().toEpochMilli()
    private val hour = 60L * 60 * 1000

    // ── actionLog ────────────────────────────────────────────────────────────

    @Test fun `completed run logs the agent's first sentence, not a bare done`() {
        val (task, outcome) = HistoryMemory.actionLog(
            "  order biryani\non swiggy ",
            "Your biryani is ordered. It arrives in 30 minutes.",
            "completed",
        )!!
        assertEquals("order biryani on swiggy", task)
        assertEquals("Your biryani is ordered.", outcome)
    }

    @Test fun `non-completed runs say how they ended regardless of the reply`() {
        assertEquals("failed", HistoryMemory.actionLog("x", "Sure!", "failed")!!.second)
        assertEquals("cancelled", HistoryMemory.actionLog("x", null, "cancelled")!!.second)
        assertEquals("paused before finishing", HistoryMemory.actionLog("x", null, "paused")!!.second)
        assertEquals("stopped: ran out of budget", HistoryMemory.actionLog("x", "…", "budget")!!.second)
    }

    @Test fun `blank goal writes nothing`() {
        assertNull(HistoryMemory.actionLog("   ", "hi", "completed"))
    }

    @Test fun `a completed run with no reply still gets an outcome`() {
        assertEquals("finished", HistoryMemory.actionLog("open maps", "", "completed")!!.second)
    }

    // ── windowOf ─────────────────────────────────────────────────────────────

    @Test fun `yesterday is the whole previous calendar day`() {
        val w = HistoryMemory.windowOf("what did I do yesterday", now, utc)!!
        val start = ZonedDateTime.of(2026, 9, 12, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(start, w.first)
        assertTrue((now - 10 * hour - 1) in w) // 23:59:59.999 on the 12th
        assertFalse((now - 10 * hour) in w) // midnight on the 13th is today
    }

    @Test fun `today runs from midnight to now`() {
        val w = HistoryMemory.windowOf("anything today?", now, utc)!!
        assertEquals(now - 10 * hour, w.first)
        assertEquals(now, w.last)
    }

    @Test fun `a query naming no time has no window`() {
        assertNull(HistoryMemory.windowOf("sister city", now, utc))
    }

    // ── recall through the tool ──────────────────────────────────────────────

    @Test fun `recall of yesterday returns that day's history, dated, and not today's`() = runTest {
        var clock = now - 20 * hour // Sat 12 Sep, 14:00
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "history_recall")) { clock }
        memory.logAction("order biryani on swiggy", "Your biryani is ordered.")
        memory.appendEpisode("You planned the AndroidWorld benchmark run.")
        clock = now - hour // today
        memory.logAction("open maps", "finished")
        memory.save(MemoryType.USER, "sister lives in Coimbatore")

        val out = MemoryAgentTools(memory, clock = { now }, zone = utc)
            .recall(JsonObject(mapOf("query" to JsonPrimitive("what did I do yesterday"))))
        val text = out.content.filterIsInstance<TextContent>().joinToString { it.text }

        assertTrue(text, text.contains("order biryani on swiggy"))
        assertTrue(text, text.contains("AndroidWorld"))
        assertTrue(text, text.contains("Sat 12 Sep, 14:00"))
        assertFalse(text, text.contains("open maps"))
    }

    // ── eviction ─────────────────────────────────────────────────────────────

    @Test fun `a long diary never evicts facts about the user`() = runTest {
        var clock = 1L
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "history_evict")) { clock++ }
        memory.save(MemoryType.USER, "sister lives in Coimbatore")
        repeat(400) { memory.logAction("task number $it", "finished") }

        val all = memory.allEntries()
        assertTrue(all.any { it.text == "sister lives in Coimbatore" })
        val logs = all.filter { it.type == MemoryType.ACTION_LOG }
        assertEquals(MemoryConsolidator.HISTORY_CAPS.getValue(MemoryType.ACTION_LOG), logs.size)
        assertTrue("newest task lines are kept", logs.any { it.text.startsWith("task number 399") })
    }

    @Test fun `the daily consolidation pass caps history per tier too`() {
        val fact = MemoryEntry("f", MemoryType.USER, "name is Dinesh", false, 0L)
        val episodes = (1..100).map { MemoryEntry("e$it", MemoryType.EPISODE, "episode $it", false, 1_000L + it) }
        val kept = MemoryConsolidator.consolidateEntries(
            listOf(fact) + episodes,
            nowEpochMs = 2_000L, ttlMs = Long.MAX_VALUE, cap = 1,
        )
        assertTrue(kept.any { it.text == "name is Dinesh" })
        assertEquals(MemoryConsolidator.HISTORY_CAPS.getValue(MemoryType.EPISODE), kept.count { it.type == MemoryType.EPISODE })
    }

    // ── set_reminder ─────────────────────────────────────────────────────────

    @Test fun `reminder times resolve the way people say them`() {
        assertEquals(now + 20 * 60_000L, HistoryMemory.dueTime(now, 20, null, 0, utc))
        assertEquals(now + 8 * hour, HistoryMemory.dueTime(now, null, "18:00", 0, utc))
        assertEquals("a passed slot today means tomorrow", now + 23 * hour, HistoryMemory.dueTime(now, null, "09:00", 0, utc))
        assertEquals(now + 23 * hour, HistoryMemory.dueTime(now, null, "09:00", 1, utc))
        assertNull(HistoryMemory.dueTime(now, 0, null, 0, utc))
        assertNull(HistoryMemory.dueTime(now, null, "6pm", 0, utc))
        assertNull(HistoryMemory.dueTime(now, null, null, 0, utc))
    }

    @Test fun `set_reminder stores it and echoes the resolved time`() = runTest {
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "remind_tool"))
        val out = MemoryAgentTools(memory, { now }, utc)
            .remind(JsonObject(mapOf("text" to JsonPrimitive("send results"), "at" to JsonPrimitive("18:00"))))
        val text = out.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertFalse(text, out.isError == true)
        assertTrue(text, text.contains("Sun 13 Sep, 18:00"))
        assertEquals(now + 8 * hour, memory.pendingCommitments().single().dueAtEpochMs)
    }

    @Test fun `a daily routine re-arms for tomorrow when it rings, and stops when dismissed`() = runTest {
        var clock = now
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "routine")) { clock }
        val armed = mutableListOf<Commitment>()
        memory.onRemind = { armed += it }
        MemoryAgentTools(memory, { clock }, utc).remind(
            JsonObject(mapOf("text" to JsonPrimitive("drink water"), "at" to JsonPrimitive("18:00"), "repeat_daily" to JsonPrimitive(true))),
        )
        val id = memory.pendingCommitments().single().id

        clock = now + 8 * hour + 1000 // it rang
        memory.markDelivered(listOf(id))
        val next = memory.pendingCommitments().single()
        assertEquals("same time tomorrow", now + 32 * hour, next.dueAtEpochMs)
        assertEquals("re-armed", next.dueAtEpochMs, armed.last().dueAtEpochMs)

        memory.completeCommitments(listOf(id)) // the user dismissed it
        assertTrue(memory.pendingCommitments().isEmpty())
    }

    @Test fun `a one-shot reminder is done once delivered`() = runTest {
        val memory = EncryptedMemoryService(EncryptedJsonStore(ctx, "oneshot")) { now }
        val c = memory.remind("call Amma", now + hour)
        memory.markDelivered(listOf(c.id))
        assertTrue(memory.pendingCommitments().isEmpty())
    }
}
