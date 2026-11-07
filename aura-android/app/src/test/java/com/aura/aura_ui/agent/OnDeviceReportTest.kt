package com.aura.aura_ui.agent

import com.aura.aura_ui.agent.ledger.StepRecord
import com.aura.aura_ui.services.RemoteRun.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceReportTest {

    private fun outcome(reason: String, verdict: String? = null, claimed: String? = null, steps: Int = 3) =
        OnDeviceReport.outcomeOf(reason, verdict, claimed, steps)

    @Test
    fun `a completed run takes the harness's verdict over the model's claim`() {
        assertEquals(Outcome.DONE, outcome("completed", "success", "success"))
        assertEquals(Outcome.PARTIAL, outcome("completed", "partial", "success"))
        assertEquals(Outcome.FAILED, outcome("completed", "fail", "success"))
    }

    @Test
    fun `an unverified run falls back to what the model claimed`() {
        assertEquals(Outcome.DONE, outcome("completed", "unverified", "success"))
        assertEquals(Outcome.PARTIAL, outcome("completed", "unverified", "partial"))
        assertEquals(Outcome.FAILED, outcome("completed", null, "failure"))
        assertEquals(Outcome.DONE, outcome("completed"))
    }

    @Test
    fun `an ending the model never chose ignores its claim`() {
        assertEquals(Outcome.STOPPED, outcome("cancelled", "success", "success"))
        assertEquals(Outcome.PAUSED, outcome("paused"))
        assertEquals(Outcome.FAILED, outcome("failed"))
    }

    @Test
    fun `running out is partial only when something got done`() {
        listOf("budget", "iterations", "quota").forEach {
            assertEquals(Outcome.PARTIAL, outcome(it, steps = 2))
            assertEquals(Outcome.FAILED, outcome(it, steps = 0))
        }
    }

    @Test
    fun `the model's report wins, and the reply stands in without one`() {
        val withReport = report(reply = "Sent it.", written = "## Sent\n- to Joyboy")
        assertEquals("## Sent\n- to Joyboy", withReport.markdown)
        assertEquals("Sent it.", report(reply = "Sent it.", written = " ").markdown)
        assertNull(report(reply = null, written = null).markdown)
    }

    @Test
    fun `recent steps read as what happened, and a failure says so`() {
        val r = report(
            reply = null,
            written = null,
            recent = listOf(
                StepRecord("launch_app", "WhatsApp", ok = true),
                StepRecord("perceive_screen", null, ok = true),
                StepRecord("tap", "Send", ok = false),
            ),
        )
        assertEquals(listOf("launch app WhatsApp", "perceive screen", "tap Send (failed)"), r.recent)
        assertTrue("an on-device card must not speak of a client", r.onDevice)
    }

    private fun report(reply: String?, written: String?, recent: List<StepRecord> = emptyList()) =
        OnDeviceReport.of(
            endReason = "completed",
            verdict = "success",
            claimed = "success",
            reply = reply,
            writtenReport = written,
            durationMs = 14_000,
            steps = 3,
            recentSteps = recent,
        )
}
