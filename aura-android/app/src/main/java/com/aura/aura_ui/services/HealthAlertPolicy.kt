package com.aura.aura_ui.services

import com.aura.mcp.McpServerHealth

/**
 * Should the user be told, right now, that the MCP server is unhealthy?
 *
 * This exists because health was removed from the activity chip — correctly, since
 * [AuraStatus] means *what the agent is doing* and a booting server is not an
 * activity — which left a real gap: a server crashing while the app was backgrounded
 * became invisible until the user happened to open the MCP screen. Silent failure is
 * worse than the wrong verb.
 *
 * **Only a crash alerts.** The other states are deliberately silent:
 *
 *  - `Starting` is transient. Alerting on it is how you get "Stuck" on a booting
 *    server all over again, just in a different widget.
 *  - `Stopped` is ambiguous: it is both "the user turned it off" and "it isn't
 *    running yet". Nagging someone who deliberately stopped the server is a false
 *    positive, and false positives train people to ignore the channel — which costs
 *    more than the case it would catch. It is also moot in the one scenario that
 *    matters: `Stopped` is published from the service's own `onDestroy`, and a dead
 *    service cannot post anything anyway.
 *  - `Listening` is healthy and must actively **clear** a previous alert, otherwise a
 *    recovered server leaves a stale "crashed" notification lying around — the same
 *    never-clears bug this whole workstream started with.
 *
 * Pure — unit-tested in HealthAlertPolicyTest.
 */
object HealthAlertPolicy {

    sealed interface Decision {
        /** Post (or update) the alert. */
        data class Alert(val reason: String) : Decision

        /** Remove any alert currently showing. */
        data object Clear : Decision

        /** Leave whatever is showing alone — the state says nothing new. */
        data object Ignore : Decision
    }

    fun decide(health: McpServerHealth): Decision = when (health) {
        is McpServerHealth.Crashed -> Decision.Alert(health.reason)
        is McpServerHealth.Listening -> Decision.Clear
        // Transient / ambiguous — see the class KDoc for why neither alerts.
        McpServerHealth.Starting, McpServerHealth.Stopped -> Decision.Ignore
    }
}
