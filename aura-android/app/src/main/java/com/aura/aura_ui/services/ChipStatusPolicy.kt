package com.aura.aura_ui.services

import com.aura.mcp.McpServerHealth

/**
 * Which [AuraStatus] should the chip show right now, given the two live activity
 * sources and the MCP server's health?
 *
 * Extracted from `AssistantForegroundService` so the decision is unit-testable —
 * it was previously a private method on a Service, which is why the health
 * mapping stayed wrong (a merely *starting* server displayed "Stuck") without
 * anything catching it.
 *
 * **Health is not activity.** [AuraStatus] describes what the agent is doing;
 * server health is infrastructure and is deliberately not mapped into it. When
 * the server is not Listening no tool can be dispatched, so there is by
 * definition no tool activity — but voice runs entirely on-device and is
 * unaffected, so it still shows.
 *
 * > **Known gap:** a server that crashes while the app is backgrounded is now
 * > invisible until the user opens the MCP screen. Health needs its own
 * > always-on surface (its own notification channel, not the activity chip).
 * > Tracked in the Phase 0 spec.
 *
 * Pure — unit-tested in ChipStatusPolicyTest.
 */
object ChipStatusPolicy {

    /**
     * Priority between the two live sources: voice (overlay) over tool dispatches
     * (MCP). They are mutually exclusive in practice — the user cannot speak a
     * command while a remote agent is running tools — so whichever is non-Idle
     * wins, with voice taking precedence on a tie.
     */
    fun decide(
        health: McpServerHealth,
        toolStatus: AuraStatus,
        voiceStatus: AuraStatus,
        pausedByHuman: Boolean = false,
    ): AuraStatus = if (pausedByHuman) {
        // Spec 2026-07-31 — while the human holds the control lock the status pill HIDES.
        // The agent is not doing anything, so a "doing" chip would simply be false, and
        // the Pause pill owns the screen at that moment. Showing both at once is exactly
        // the confusion the separate-components split exists to prevent.
        AuraStatus.Idle
    } else {
        decideActivity(health, toolStatus, voiceStatus)
    }

    private fun decideActivity(
        health: McpServerHealth,
        toolStatus: AuraStatus,
        voiceStatus: AuraStatus,
    ): AuraStatus = when (health) {
        McpServerHealth.Stopped,
        McpServerHealth.Starting,
        is McpServerHealth.Crashed,
        -> voiceStatus
        is McpServerHealth.Listening -> if (voiceStatus != AuraStatus.Idle) voiceStatus else toolStatus
    }
}
