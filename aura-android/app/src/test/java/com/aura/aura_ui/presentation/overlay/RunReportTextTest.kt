package com.aura.aura_ui.presentation.overlay

import com.aura.aura_ui.services.RemoteRun
import com.aura.aura_ui.services.RemoteRun.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RunReportTextTest {

    private fun report(
        outcome: Outcome = Outcome.DONE,
        summary: String? = null,
        markdown: String? = null,
        recent: List<String> = emptyList(),
        onDevice: Boolean = true,
    ) = RemoteRun.Report(outcome, "AURA", 14_000, 3, summary, markdown, recent, onDevice)

    @Test
    fun `the log keeps what the card showed`() {
        assertEquals(
            "Task complete · AURA · 14s · 3 steps\n\nOpened it.\n\n## Sent\n- to Joyboy",
            reportText(report(summary = "Opened it.", markdown = "## Sent\n- to Joyboy")),
        )
    }

    @Test
    fun `a run with no report lists its steps, as the card does`() {
        assertEquals(
            "Stopped · AURA · 14s · 3 steps\n\n- launch app WhatsApp\n- tap Send",
            reportText(report(Outcome.STOPPED, recent = listOf("launch app WhatsApp", "tap Send"))),
        )
    }

    @Test
    fun `remote-client wording never lands on an on-device card`() {
        Outcome.values().forEach { assertNull(footnoteFor(report(it))) }
        assertNotNull(footnoteFor(report(Outcome.STOPPED, onDevice = false)))
    }
}
