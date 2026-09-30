package com.aura.aura_ui.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConsolidatorTest {
    private fun entry(text: String, age: Long) =
        MemoryEntry("id-$text-$age", MemoryType.PROJECT, text, false, age)

    @Test fun `drops entries older than ttl`() {
        val kept = MemoryConsolidator.consolidateEntries(
            listOf(entry("old", 0L), entry("fresh", 9_000L)),
            nowEpochMs = 10_000L, ttlMs = 5_000L, cap = 100,
        )
        assertEquals(listOf("fresh"), kept.map { it.text })
    }

    @Test fun `de-duplicates identical text keeping the newest`() {
        val kept = MemoryConsolidator.consolidateEntries(
            listOf(entry("dup", 1L), entry("dup", 2L)),
            nowEpochMs = 3L, ttlMs = Long.MAX_VALUE, cap = 100,
        )
        assertEquals(1, kept.size)
        assertEquals(2L, kept.single().createdAtEpochMs)
    }

    @Test fun `caps to the most recent N`() {
        val many = (1..50).map { entry("e$it", it.toLong()) }
        val kept = MemoryConsolidator.consolidateEntries(many, nowEpochMs = 100L, ttlMs = Long.MAX_VALUE, cap = 10)
        assertEquals(10, kept.size)
        assertTrue(kept.all { it.createdAtEpochMs > 40 })
    }

    /** Pinning is a promise to the user; a background worker silently expiring one breaks it. */
    @Test fun `pinned entries survive the ttl and the cap`() {
        // The pinned entry is deliberately ANCIENT (t=0, far outside the 5 s ttl) — that is the
        // ttl half of the claim. The other 50 must be FRESH (inside the ttl), or they all expire
        // before the cap is ever consulted and the cap half of this test proves nothing: kept
        // would be [pinned] alone, size 1. That was the bug in this test, not in the consolidator.
        val pinned = entry("pinned", 0L).copy(pinned = true)
        val fresh = (1..50).map { entry("e$it", 5_000L + it) }
        val kept = MemoryConsolidator.consolidateEntries(
            listOf(pinned) + fresh,
            nowEpochMs = 10_000L, ttlMs = 5_000L, cap = 5,
        )
        assertTrue("pinning is a promise — the ttl must not silently break it", kept.any { it.text == "pinned" })
        // Cap is honoured overall, and the pinned entry is one of the survivors rather than an
        // extra on top of the cap.
        assertEquals(5, kept.size)
    }

    @Test fun `a pinned entry is never evicted by the cap, even when the cap is full`() {
        // cap=1 with 50 fresh competitors: the pinned entry must be the one that survives.
        val pinned = entry("pinned", 0L).copy(pinned = true)
        val kept = MemoryConsolidator.consolidateEntries(
            listOf(pinned) + (1..50).map { entry("e$it", 5_000L + it) },
            nowEpochMs = 10_000L, ttlMs = 5_000L, cap = 1,
        )
        assertEquals(listOf("pinned"), kept.map { it.text })
    }

    /** Who the user is does not go stale on a timer; a past conversation does. */
    @Test fun `identity tiers are exempt from the ttl but episodes are not`() {
        val fact = MemoryEntry("f", MemoryType.USER, "name is Dinesh", false, 0L)
        val episode = MemoryEntry("e", MemoryType.EPISODE, "you asked about trains", false, 0L)
        val kept = MemoryConsolidator.consolidateEntries(
            listOf(fact, episode),
            nowEpochMs = 10_000L, ttlMs = 5_000L, cap = 100,
        )
        assertEquals(listOf("name is Dinesh"), kept.map { it.text })
    }

    @Test fun `identical text under two tiers is two different claims`() {
        val kept = MemoryConsolidator.consolidateEntries(
            listOf(
                MemoryEntry("a", MemoryType.USER, "dentist", false, 1L),
                MemoryEntry("b", MemoryType.EPISODE, "dentist", false, 1L),
            ),
            nowEpochMs = 2L, ttlMs = Long.MAX_VALUE, cap = 100,
        )
        assertEquals(2, kept.size)
    }

    @Test fun `learnings consolidation keeps highest successCount up to cap`() {
        val ls = (1..20).map { LearningEntry("com.x", "navigate", listOf("s$it"), it.toLong(), successCount = it) }
        val kept = MemoryConsolidator.consolidateLearnings(ls, cap = 5)
        assertEquals(5, kept.size)
        assertTrue(kept.minOf { it.successCount } >= 16)
    }

    // ── Spec 2026-07-17: decay-prune learned lessons (per-kind TTLs) ──

    @Test fun `decayLearnings prunes paths older than the path TTL`() {
        val now = 1_000_000_000_000L
        val fresh = LearningEntry("com.a", "navigate", listOf("s"), recordedAtEpochMs = now - 1)
        val stale = LearningEntry("com.a", "navigate", listOf("t"), recordedAtEpochMs = now - LearningsDecay.PATH_TTL_MS - 1)
        assertEquals(listOf(fresh), MemoryConsolidator.decayLearnings(listOf(fresh, stale), now))
    }

    @Test fun `decayFacts uses per-kind TTLs`() {
        val now = 1_000_000_000_000L
        fun fact(kind: FactKind, ageMs: Long) =
            AppFactEntry("com.a", kind, "x-$kind-$ageMs", recordedAtEpochMs = now - ageMs)
        val kept = MemoryConsolidator.decayFacts(
            listOf(
                fact(FactKind.RECOVERY, LearningsDecay.RECOVERY_TTL_MS - 1), // kept
                fact(FactKind.RECOVERY, LearningsDecay.RECOVERY_TTL_MS + 1), // pruned
                fact(FactKind.QUIRK, LearningsDecay.QUIRK_TTL_MS - 1),       // kept
                fact(FactKind.ANTI_PATTERN, LearningsDecay.ANTI_TTL_MS + 1), // pruned
            ),
            now,
        )
        assertEquals(2, kept.size)
    }
}
