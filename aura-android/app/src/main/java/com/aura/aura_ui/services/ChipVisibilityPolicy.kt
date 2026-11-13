package com.aura.aura_ui.services

/**
 * Whether `AssistantForegroundService` shows its status chip or hides behind a silent placeholder.
 *
 * - Contract: [Mode.CHIP] only while the agent is working ([status] is not Idle). The service
 *   itself stays up (it hosts the MCP server), but Android only requires *a* notification, so
 *   idle it posts an IMPORTANCE_MIN one with no status-bar icon.
 * - Why the automation pill wins: during an on-device run the overlay posts its own chip with
 *   step progress and Cancel; a second AURA chip would double up.
 *
 * Pure; unit-tested in ChipVisibilityPolicyTest.
 */
object ChipVisibilityPolicy {

    enum class Mode {
        /** Show the status chip (promoted on Android 16+, a normal silent notification below). */
        CHIP,

        /** Keep the foreground service alive behind an IMPORTANCE_MIN placeholder. */
        SILENT,
    }

    fun decide(automationPillActive: Boolean, status: AuraStatus): Mode = when {
        automationPillActive -> Mode.SILENT
        status != AuraStatus.Idle -> Mode.CHIP
        else -> Mode.SILENT
    }
}
