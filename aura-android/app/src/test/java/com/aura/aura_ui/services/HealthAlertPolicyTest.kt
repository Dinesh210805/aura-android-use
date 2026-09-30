package com.aura.aura_ui.services

import com.aura.aura_ui.services.HealthAlertPolicy.Decision
import com.aura.mcp.McpServerHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Closes the gap opened by removing health from the activity chip: a server that
 * crashes while the app is backgrounded must still reach the user.
 *
 * The tension this policy balances: alert too eagerly and the channel becomes noise
 * people swipe away without reading, which is worse than not having it. So exactly
 * one state alerts, and exactly one clears.
 */
class HealthAlertPolicyTest {

    private val listening = McpServerHealth.Listening(
        port = 4816,
        host = "127.0.0.1",
        reachableAddresses = emptyList(),
    )

    @Test
    fun `a crash alerts and carries the reason`() {
        val decision = HealthAlertPolicy.decide(McpServerHealth.Crashed(reason = "Port 8765 in use"))

        assertEquals(Decision.Alert("Port 8765 in use"), decision)
    }

    @Test
    fun `recovery clears the alert`() {
        // Without this a recovered server leaves a stale "crashed" notification —
        // the same never-clears failure this workstream began with.
        assertEquals(Decision.Clear, HealthAlertPolicy.decide(listening))
    }

    @Test
    fun `a starting server does not alert`() {
        // Alerting here would recreate "Stuck on a booting server" in a new widget.
        assertEquals(Decision.Ignore, HealthAlertPolicy.decide(McpServerHealth.Starting))
    }

    @Test
    fun `a stopped server does not alert`() {
        // Ambiguous: also means "the user turned it off". A false positive here
        // trains people to ignore the channel.
        assertEquals(Decision.Ignore, HealthAlertPolicy.decide(McpServerHealth.Stopped))
    }

    @Test
    fun `starting does not clear an existing crash alert`() {
        // A crash followed by an auto-restart attempt must keep the alert up until
        // the server actually reaches Listening. Ignore != Clear.
        assertEquals(Decision.Ignore, HealthAlertPolicy.decide(McpServerHealth.Starting))
    }

    @Test
    fun `a second crash updates the alert with the newer reason`() {
        val first = HealthAlertPolicy.decide(McpServerHealth.Crashed(reason = "first"))
        val second = HealthAlertPolicy.decide(McpServerHealth.Crashed(reason = "second"))

        assertEquals(Decision.Alert("first"), first)
        assertEquals(Decision.Alert("second"), second)
    }
}
