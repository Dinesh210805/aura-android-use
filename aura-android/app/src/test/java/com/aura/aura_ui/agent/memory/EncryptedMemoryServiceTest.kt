package com.aura.aura_ui.agent.memory

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EncryptedMemoryServiceTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private fun svc(name: String, clock: () -> Long = { 1000L }) =
        EncryptedMemoryService(EncryptedJsonStore(ctx, name), clock)

    @Test fun `save scrubs pii on write`() = runTest {
        val s = svc("mem_scrub")
        s.save(MemoryType.USER, "user email is bob@gmail.com")
        assertTrue(s.allEntries().single().text.contains("<email>"))
    }

    @Test fun `recall ranks by keyword overlap`() = runTest {
        val s = svc("mem_recall")
        s.save(MemoryType.USER, "likes spotify liked songs")
        s.save(MemoryType.USER, "prefers dark mode")
        val hits = s.recall("spotify songs", limit = 1)
        assertEquals(1, hits.size)
        assertTrue(hits.single().text.contains("spotify"))
    }

    @Test fun `buildSessionContext excludes sensitive but recall can reach it`() = runTest {
        val s = svc("mem_sensitive")
        s.save(MemoryType.USER, "the message said: meet at five")  // sensitive
        s.save(MemoryType.USER, "name is Dinesh")                  // not sensitive
        val ctxBlock = s.buildSessionContext(nowEpochMs = 2000L)
        assertFalse(ctxBlock.contains("meet at five"))
        assertTrue(s.recall("meet").isNotEmpty())                  // explicit recall still finds it
    }

    @Test fun `remind then dueCommitments filters by time`() = runTest {
        val s = svc("mem_remind")
        s.remind("call the dentist", dueAtEpochMs = 5000L)
        assertTrue(s.dueCommitments(nowEpochMs = 4000L).isEmpty())
        assertEquals(1, s.dueCommitments(nowEpochMs = 6000L).size)
    }

    @Test fun `completeCommitments marks done and stops re-surfacing - M3`() = runTest {
        val s = svc("mem_complete")
        val c = s.remind("call the dentist", dueAtEpochMs = 1000L)
        s.completeCommitments(listOf(c.id))
        assertTrue(s.dueCommitments(nowEpochMs = 2000L).isEmpty())
        assertFalse(s.buildSessionContext(nowEpochMs = 2000L).contains("dentist"))
        // Still visible (as done) to the Settings inspector.
        assertTrue(s.allCommitments().single().done)
    }

    @Test fun `due reminders in session context carry the acknowledge instruction - M3`() = runTest {
        val s = svc("mem_ack")
        s.remind("water the plants", dueAtEpochMs = 1000L)
        val ctxBlock = s.buildSessionContext(nowEpochMs = 2000L)
        assertTrue(ctxBlock.contains("water the plants"))
        assertTrue("context must tell the model how to acknowledge", ctxBlock.contains("get_commitments"))
    }

    @Test fun `clear empties the store`() = runTest {
        val s = svc("mem_clear")
        s.save(MemoryType.USER, "x")
        s.clear()
        assertTrue(s.allEntries().isEmpty())
    }

    // ── tier separation (spec 2026-07-28) ────────────────────────────────────

    /**
     * The regression this whole split exists for: an episode summary used to be rendered as a
     * bullet under "What I remember", so the model was handed last week's conversation as
     * present-tense knowledge and would report it as something it had just done.
     */
    @Test fun `episodes are framed as the past, never as what AURA knows`() = runTest {
        val s = svc("mem_tier_episode")
        s.save(MemoryType.USER, "name is Dinesh")
        s.appendEpisode("You asked about the electricity bill")
        val ctxBlock = s.buildSessionContext(nowEpochMs = 2000L)

        val knows = ctxBlock.substringAfter("# What I know about them").substringBefore("#")
        assertTrue("durable fact belongs in the identity block", knows.contains("Dinesh"))
        assertFalse("an episode must not read as current knowledge", knows.contains("electricity"))
        assertTrue(ctxBlock.contains("Earlier conversations"))
        assertTrue("the header must say these are not happening now", ctxBlock.contains("NOT things happening now"))
    }

    @Test fun `action logs stay out of the unprompted context but recall reaches them`() = runTest {
        val s = svc("mem_tier_action")
        s.logAction("send whatsapp to mom", "success")
        assertEquals("", s.buildSessionContext(nowEpochMs = 2000L))
        assertTrue(s.recall("whatsapp").isNotEmpty())
    }

    @Test fun `legacy PROJECT rows are reclassified on read`() = runTest {
        val s = svc("mem_migrate")
        s.save(MemoryType.PROJECT, "You asked about trains")
        s.save(MemoryType.PROJECT, "book a cab → failed")
        val types = s.allEntries().associate { it.text to it.type }
        assertEquals(MemoryType.EPISODE, types["You asked about trains"])
        assertEquals(MemoryType.ACTION_LOG, types["book a cab → failed"])
        assertFalse("nothing may stay in the legacy bucket", s.allEntries().any { it.type == MemoryType.PROJECT })
    }

    // ── supersession + pinning ───────────────────────────────────────────────

    @Test fun `restating a fact strengthens it in place instead of duplicating`() = runTest {
        val s = svc("mem_restate")
        val first = s.save(MemoryType.USER, "prefers short answers")
        val again = s.save(MemoryType.USER, "prefers short answers")
        assertEquals("id must survive so a pin survives", first.id, again.id)
        assertEquals(2, again.confirmations)
        assertEquals(1, s.allEntries().size)
    }

    /** Conservative on purpose: silently fusing two different facts is worse than a duplicate. */
    @Test fun `similar but different facts stay separate`() = runTest {
        val s = svc("mem_distinct")
        s.save(MemoryType.USER, "sister lives in Coimbatore")
        s.save(MemoryType.USER, "brother lives in Coimbatore")
        assertEquals(2, s.allEntries().size)
    }

    @Test fun `episodes never merge even when worded alike`() = runTest {
        val s = svc("mem_episode_nomerge")
        s.appendEpisode("You asked about the bill")
        s.appendEpisode("You asked about the bill")
        assertEquals(2, s.allEntries().size)
    }

    @Test fun `pinned memories rank first in the context block`() = runTest {
        val s = svc("mem_pin")
        s.save(MemoryType.USER, "likes filter coffee")
        val pin = s.save(MemoryType.USER, "allergic to peanuts")
        s.setPinned(pin.id, true)
        val identity = s.buildSessionContext(nowEpochMs = 2000L)
            .substringAfter("# What I know about them\n")
        assertTrue("pinned entry must lead", identity.startsWith("• allergic to peanuts"))
    }

    @Test fun `delete removes one memory without touching the rest`() = runTest {
        val s = svc("mem_delete")
        val a = s.save(MemoryType.USER, "likes filter coffee")
        s.save(MemoryType.USER, "wakes up early")
        s.delete(a.id)
        assertEquals(listOf("wakes up early"), s.allEntries().map { it.text })
    }

    // ── speech style ─────────────────────────────────────────────────────────

    /** Style shapes AURA's own voice, so it goes to the persona — never into the recited context. */
    @Test fun `style notes feed the persona and stay out of the context block`() = runTest {
        val s = svc("mem_style")
        s.save(MemoryType.STYLE, "mixes Tamil and English in one sentence")
        assertEquals(listOf("mixes Tamil and English in one sentence"), s.styleNotes())
        assertFalse(s.buildSessionContext(nowEpochMs = 2000L).contains("Tamil"))
    }
}
