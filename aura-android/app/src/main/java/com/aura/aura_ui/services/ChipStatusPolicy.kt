package com.aura.aura_ui.services

import com.aura.mcp.McpServerHealth

/**
 * Which [AuraStatus] the status chip shows right now: what an MCP client is making the phone do.
 *
 * - Contract: returns [AuraStatus.Idle] whenever nothing is driving the phone, and
 *   [ChipVisibilityPolicy] hides the chip on Idle. So "Ready" is never shown.
 * - Why health is ignored: a stopped or booting server is infrastructure, not activity, and has
 *   its own alert ([McpHealthNotifier]). When the server is not Listening no tool can be
 *   dispatched, so any tool status left over is stale.
 * - Why voice is not here: talking to AURA is shown by the overlay itself. The chip is only for
 *   the agent working on the phone.
 *
 * Pure; unit-tested in ChipStatusPolicyTest.
 */
object ChipStatusPolicy {

    fun decide(
        health: McpServerHealth,
        toolStatus: AuraStatus,
        pausedByHuman: Boolean = false,
    ): AuraStatus = when {
        // While the human holds the control lock the Pause pill owns the screen; the agent is
        // not doing anything, so a "doing" chip would be false.
        pausedByHuman -> AuraStatus.Idle
        health is McpServerHealth.Listening -> toolStatus
        else -> AuraStatus.Idle
    }
}
