package com.aura.aura_ui.agent.ledger

import android.content.Context
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RunLedgerStoreTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun store(name: String, now: Long = 1_000_000L) =
        RunLedgerStore(EncryptedJsonStore(ctx, name), clock = { now })

    private fun ledger(
        runId: String,
        startedAtMs: Long = 1_000_000L,
        endReason: String? = null,
        endedAtMs: Long? = null,
        planSteps: List<PlanStep> = listOf(PlanStep("open app")),
        totalSteps: Int = 0,
    ) = RunLedger(
        runId = runId,
        goal = "goal-$runId",
        provider = "GROQ",
        modelId = "llama",
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        endReason = endReason,
        planSteps = planSteps,
        totalSteps = totalSteps,
    )

    @Test fun `save then get round-trips the ledger`() {
        val s = store("rl_roundtrip")
        val l = ledger("r1").copy(facts = listOf("fact A"), deadEnds = listOf("dead end B"))
        s.save(l)
        assertEquals(l, s.get("r1"))
    }

    @Test fun `an app-death ledger with a plan is resumable`() {
        val s = store("rl_appdeath")
        s.save(ledger("r1", endReason = null))
        assertEquals("r1", s.latestResumable()?.runId)
    }

    @Test fun `a completed ledger is not resumable`() {
        val s = store("rl_completed")
        s.save(ledger("r1", endReason = "completed"))
        assertNull(s.latestResumable())
    }

    @Test fun `budget, failed and cancelled ledgers are resumable`() {
        listOf("budget", "failed", "cancelled").forEach { reason ->
            val s = store("rl_reason_$reason")
            s.save(ledger("r1", endReason = reason, endedAtMs = 1_000_500L))
            assertEquals("resumable for $reason", "r1", s.latestResumable()?.runId)
        }
    }

    @Test fun `dismiss marks abandoned and stops re-offering even for an app-death ledger`() {
        val s = store("rl_dismiss")
        s.save(ledger("r1", endReason = null)) // app death: endedAtMs null
        assertEquals("r1", s.latestResumable()?.runId)
        s.dismiss("r1")
        assertNull("abandoned ledger must not re-offer", s.latestResumable())
        assertEquals("abandoned", s.get("r1")?.endReason)
    }

    @Test fun `save upserts by runId — latest snapshot wins, no duplicate`() {
        val s = store("rl_upsert")
        s.save(ledger("r1", totalSteps = 1))
        s.save(ledger("r1", totalSteps = 7))
        assertEquals(1, s.all().count { it.runId == "r1" })
        assertEquals(7, s.get("r1")?.totalSteps)
    }

    @Test fun `store caps at the most recent 5 ledgers by start time`() {
        val s = store("rl_cap")
        (1..7).forEach { i -> s.save(ledger("r$i", startedAtMs = 1_000_000L + i)) }
        val kept = s.all().map { it.runId }.toSet()
        assertEquals(RunLedgerStore.MAX_LEDGERS, s.all().size)
        assertTrue("newest kept", kept.containsAll(setOf("r3", "r4", "r5", "r6", "r7")))
        assertTrue("oldest evicted", kept.none { it == "r1" || it == "r2" })
    }

    @Test fun `a ledger older than the resume window is not offered`() {
        val now = 100_000_000L
        val s = store("rl_stale", now = now)
        s.save(ledger("r1", startedAtMs = now - RunLedgerStore.RESUMABLE_WINDOW_MS - 1, endReason = null))
        assertNull(s.latestResumable())
    }

    @Test fun `a trivial ledger with no plan and too few steps is not offered`() {
        val s = store("rl_trivial")
        s.save(ledger("r1", endReason = null, planSteps = emptyList(), totalSteps = 2))
        assertNull(s.latestResumable())
    }

    @Test fun `a plan-less ledger with enough recorded steps is resumable`() {
        val s = store("rl_stepsonly")
        s.save(ledger("r1", endReason = null, planSteps = emptyList(), totalSteps = 3))
        assertEquals("r1", s.latestResumable()?.runId)
    }

    @Test fun `latestResumable returns the newest among several resumable`() {
        val s = store("rl_newest", now = 1_000_900L)
        s.save(ledger("older", startedAtMs = 1_000_100L, endReason = "failed", endedAtMs = 1_000_200L))
        s.save(ledger("newer", startedAtMs = 1_000_800L, endReason = null))
        assertEquals("newer", s.latestResumable()?.runId)
    }

    @Test fun `seededResume forks a fresh run carrying the prior plan, facts and dead ends`() {
        val prior = ledger("old").copy(
            planSteps = listOf(PlanStep("a", PlanStepStatus.DONE), PlanStep("b")),
            facts = listOf("fact A"),
            deadEnds = listOf("dead end B"),
            recentSteps = listOf(StepRecord(tool = "tap", label = "Compose", ok = true)),
            planVersion = 2,
            totalSteps = 4,
            failedSteps = 1,
            endReason = "budget",
            endedAtMs = 1_000_500L,
        )
        val fresh = prior.seededResume(newRunId = "new", nowMs = 2_000_000L)
        assertEquals("new", fresh.runId)
        assertEquals("old", fresh.resumedFrom)
        assertEquals(2_000_000L, fresh.startedAtMs)
        assertNull("fresh run is live again", fresh.endedAtMs)
        assertNull(fresh.endReason)
        assertEquals(prior.goal, fresh.goal)
        assertEquals(prior.planSteps, fresh.planSteps)
        assertEquals(prior.facts, fresh.facts)
        assertEquals(prior.deadEnds, fresh.deadEnds)
        assertEquals(prior.recentSteps, fresh.recentSteps)
        assertEquals(prior.planVersion, fresh.planVersion)
    }
}
