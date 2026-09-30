package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "say it once" rule. Asking the model to remember what it has already said does not work —
 * it repeats the standing-by line every turn — so the count is kept in code and the model is only
 * cued when the gate opens.
 */
class TaskNarrationGateTest {

    /** Breaks if the starting beat stops being one-shot: AURA would announce the same task twice. */
    @Test fun `a beat can be claimed exactly once per task`() {
        val gate = TaskNarrationGate()
        gate.onTaskStarted("t1")
        assertTrue(gate.claim(TaskBeat.STARTING))
        assertFalse(gate.claim(TaskBeat.STARTING))
        assertFalse(gate.claim(TaskBeat.STARTING))
    }

    /** Breaks if claiming one beat consumes the others — the whole point is three separate moments. */
    @Test fun `each beat is claimed independently`() {
        val gate = TaskNarrationGate()
        gate.onTaskStarted("t1")
        assertTrue(gate.claim(TaskBeat.STARTING))
        assertTrue(gate.claim(TaskBeat.ONGOING))
        assertTrue(gate.claim(TaskBeat.FINISHED))
    }

    /** Breaks if beats are not re-armed for a new task: the second task of a session would run
     *  completely silently, which is worse than repeating. */
    @Test fun `a new task re-arms every beat`() {
        val gate = TaskNarrationGate()
        gate.onTaskStarted("t1")
        gate.claim(TaskBeat.STARTING)
        gate.claim(TaskBeat.ONGOING)

        gate.onTaskStarted("t2")
        assertTrue(gate.claim(TaskBeat.STARTING))
        assertTrue(gate.claim(TaskBeat.ONGOING))
    }

    /** Breaks if the same task id re-arms the gate. A duplicate start signal (a reconnect, a
     *  repeated tracker callback) must not license a second announcement. */
    @Test fun `re-announcing the same task id does not re-arm it`() {
        val gate = TaskNarrationGate()
        gate.onTaskStarted("t1")
        assertTrue(gate.claim(TaskBeat.STARTING))
        gate.onTaskStarted("t1")
        assertFalse(gate.claim(TaskBeat.STARTING))
    }

    /** Breaks if the gate opens with no task running — AURA would narrate a task that does not
     *  exist, which is the failure mode users read as hallucination. */
    @Test fun `nothing can be claimed before a task starts`() {
        val gate = TaskNarrationGate()
        assertFalse(gate.claim(TaskBeat.STARTING))
        assertFalse(gate.claim(TaskBeat.ONGOING))
    }

    /** Breaks if a finished task keeps its gate open: the standing-by line could land after the
     *  task already reported its outcome. */
    @Test fun `nothing can be claimed after the task finishes`() {
        val gate = TaskNarrationGate()
        gate.onTaskStarted("t1")
        assertTrue(gate.claim(TaskBeat.FINISHED))
        gate.onTaskFinished()
        assertFalse(gate.claim(TaskBeat.ONGOING))
    }

    /** Breaks if the gate stops reporting which task it is narrating — the cue text needs the label
     *  and a mismatch would describe the wrong task. */
    @Test fun `the gate reports the task it is currently narrating`() {
        val gate = TaskNarrationGate()
        assertEquals(null, gate.currentTaskId)
        gate.onTaskStarted("t1")
        assertEquals("t1", gate.currentTaskId)
        gate.onTaskFinished()
        assertEquals(null, gate.currentTaskId)
    }
}
