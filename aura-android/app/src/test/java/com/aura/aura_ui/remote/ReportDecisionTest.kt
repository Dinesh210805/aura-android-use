package com.aura.aura_ui.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDecisionTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun `opt-out beats every other reason to write`() {
        // Deliberately the case that would otherwise be the strongest yes: a
        // brand-new install on a new version. Opt-out has to win over all of it,
        // or the toggle is decorative.
        assertFalse(
            shouldReport(
                diagnosticsEnabled = false,
                nowMs = now,
                lastReportMs = 0L,
                lastReportedVersionCode = -1L,
                currentVersionCode = 358L,
            ),
        )
    }

    @Test
    fun `a never-reported install writes immediately`() {
        assertTrue(
            shouldReport(
                diagnosticsEnabled = true,
                nowMs = now,
                lastReportMs = 0L,
                lastReportedVersionCode = -1L,
                currentVersionCode = 358L,
            ),
        )
    }

    @Test
    fun `an upgrade writes at once, without waiting for the interval`() {
        // One minute after the last write — far inside the throttle. The version
        // change is the whole point of the dashboard, so it must not be delayed.
        assertTrue(
            shouldReport(
                diagnosticsEnabled = true,
                nowMs = now,
                lastReportMs = now - 60_000L,
                lastReportedVersionCode = 357L,
                currentVersionCode = 358L,
            ),
        )
    }

    @Test
    fun `a relaunch inside the interval on the same version does not write`() {
        // The case that protects the daily quota: a crash-loop restarting the
        // process repeatedly must not produce one write per restart.
        assertFalse(
            shouldReport(
                diagnosticsEnabled = true,
                nowMs = now,
                lastReportMs = now - hour,
                lastReportedVersionCode = 358L,
                currentVersionCode = 358L,
            ),
        )
    }

    @Test
    fun `once the interval elapses the row refreshes`() {
        assertTrue(
            shouldReport(
                diagnosticsEnabled = true,
                nowMs = now,
                lastReportMs = now - (24L * hour),
                lastReportedVersionCode = 358L,
                currentVersionCode = 358L,
            ),
        )
    }

    @Test
    fun `a clock that jumped backwards does not wedge reporting`() {
        // Construct the input on purpose rather than trust the branch exists:
        // lastReportMs in the future makes elapsed negative, and a naive
        // `elapsed >= interval` would then refuse to write until real time
        // caught up — which for a manually-set date could be years.
        assertTrue(
            shouldReport(
                diagnosticsEnabled = true,
                nowMs = now,
                lastReportMs = now + (48L * hour),
                lastReportedVersionCode = 358L,
                currentVersionCode = 358L,
            ),
        )
    }

    @Test
    fun `a downgrade counts as a version change`() {
        // Sideloaded installs can go backwards; the dashboard should show the
        // version actually running, not the highest ever seen.
        assertTrue(
            shouldReport(
                diagnosticsEnabled = true,
                nowMs = now,
                lastReportMs = now - 60_000L,
                lastReportedVersionCode = 358L,
                currentVersionCode = 357L,
            ),
        )
    }

    @Test
    fun `a changed row writes at once`() {
        // The user just signed in: the owner should see the email without waiting a day.
        assertTrue(
            shouldReport(
                diagnosticsEnabled = true,
                nowMs = now,
                lastReportMs = now - 60_000L,
                lastReportedVersionCode = 358L,
                currentVersionCode = 358L,
                rowChanged = true,
            ),
        )
    }

    @Test
    fun `twelve hours later on the same row does not write`() {
        // The quota rule: at most one heartbeat per install per day.
        assertFalse(
            shouldReport(
                diagnosticsEnabled = true,
                nowMs = now,
                lastReportMs = now - (12L * hour),
                lastReportedVersionCode = 358L,
                currentVersionCode = 358L,
            ),
        )
    }
}
