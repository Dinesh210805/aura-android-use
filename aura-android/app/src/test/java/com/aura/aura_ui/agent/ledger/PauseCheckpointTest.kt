package com.aura.aura_ui.agent.ledger

import android.content.Context
import com.aura.aura_ui.agent.memory.EncryptedJsonStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PauseCheckpointTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun ledger(endReason: String?) = RunLedger(
        runId = "r1",
        goal = "message mum",
        provider = "GROQ",
        modelId = "llama",
        startedAtMs = 1_000_000L,
        endReason = endReason,
        planSteps = listOf(PlanStep("open WhatsApp")),
    )

    /**
     * The end-to-end claim, and the one that would have caught the bug: a run interrupted by
     * the user and then stamped the old way is invisible to the resume offer, so the task is
     * gone. Goes through the real store rather than asserting on the constant, because the
     * constant being in a set proves nothing about what `latestResumable` actually returns.
     */
    @Test
    fun `a paused run is offered for resume where a completed one is not`() {
        val store = RunLedgerStore(
            EncryptedJsonStore(ctx, "rl_pause_offer"),
            clock = { 1_000_000L },
        )

        store.save(ledger(endReason = PauseCheckpoint.COMPLETED))
        assertNull(
            "a genuinely completed run must never be re-offered",
            store.latestResumable(),
        )

        store.save(ledger(endReason = PauseCheckpoint.PAUSED))
        assertNotNull(
            "the user picked up their phone mid-task and the run vanished — this is the bug",
            store.latestResumable(),
        )
    }

    @Test
    fun `the checkpoint note names the task so a resumed run knows what was interrupted`() {
        val note = PauseCheckpoint.checkpointNote("message mum")
        assertTrue("the note must name the task: $note", note.contains("message mum"))
        assertTrue("the note must say it did not finish: $note", note.contains("not finished"))
    }

    @Test
    fun `the checkpoint note survives an unknown task without printing null`() {
        for (task in listOf(null, "", "   ")) {
            val note = PauseCheckpoint.checkpointNote(task)
            assertTrue("leaked a placeholder for task=$task: $note", !note.contains("null"))
            assertTrue("still has to say what happened: $note", note.contains("Paused"))
        }
    }

    @Test
    fun `a run still paused at the end cannot claim it completed`() {
        assertEquals(
            PauseCheckpoint.PAUSED,
            PauseCheckpoint.endReasonFor(computed = "completed", pausedByHuman = true),
        )
    }

    @Test
    fun `a run that finished normally keeps its own end reason`() {
        assertEquals(
            "completed",
            PauseCheckpoint.endReasonFor(computed = "completed", pausedByHuman = false),
        )
    }

    @Test
    fun `a real failure is not relabelled as a pause`() {
        // "failed" and "budget" are already resumable and already honest about what
        // happened. Overwriting them with "paused" would lose the diagnosis for no gain.
        assertEquals(
            "failed",
            PauseCheckpoint.endReasonFor(computed = "failed", pausedByHuman = true),
        )
        assertEquals(
            "budget",
            PauseCheckpoint.endReasonFor(computed = "budget", pausedByHuman = true),
        )
    }

    @Test
    fun `a cancelled run stays cancelled even if the human also held the lock`() {
        assertEquals(
            "cancelled",
            PauseCheckpoint.endReasonFor(computed = "cancelled", pausedByHuman = true),
        )
    }

    @Test
    fun `a paused run is offered for resume, which is the entire point`() {
        // The bug this closes: paused runs were stamped "completed", and "completed" is
        // deliberately absent from RESUMABLE_END_REASONS, so the task vanished silently.
        assertTrue(
            "a paused run must be resumable or the checkpoint is decorative",
            PauseCheckpoint.PAUSED in RunLedgerStore.RESUMABLE_END_REASONS,
        )
    }
}
