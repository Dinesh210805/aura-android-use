package com.aura.aura_ui.services

/**
 * When should the AssistantForegroundService's Promoted Ongoing chip actually
 * render as a pill?
 *
 * The service itself may stay alive forever (keep-alive), but the pill is a
 * user-facing surface: it must appear only when something is genuinely
 * happening — the AURA app is open, an MCP client is connected/driving, or a
 * voice/automation flow is live. A permanent "Ready" pill in the notch is
 * noise (the user's complaint), and DURING automation the overlay posts its
 * own richer pill (progress + Cancel), so this chip must yield to avoid the
 * double-pill.
 *
 * Pure — unit-tested in ChipVisibilityPolicyTest.
 */
object ChipVisibilityPolicy {

    enum class Mode {
        /** Render the status chip (promoted pill). */
        CHIP,

        /** Keep the FGS alive with an invisible IMPORTANCE_MIN placeholder. */
        SILENT,
    }

    fun decide(
        automationPillActive: Boolean,
        appInForeground: Boolean,
        connectedClients: Int,
        toolStatus: AuraStatus,
        voiceStatus: AuraStatus,
    ): Mode = when {
        // The overlay's automation pill (progress + Cancel) owns the notch —
        // a second chip would double up.
        automationPillActive -> Mode.SILENT
        // Something is genuinely happening or being watched.
        appInForeground -> Mode.CHIP
        connectedClients > 0 -> Mode.CHIP
        toolStatus != AuraStatus.Idle -> Mode.CHIP
        voiceStatus != AuraStatus.Idle -> Mode.CHIP
        // Backgrounded, idle, nobody connected → invisible keep-alive.
        else -> Mode.SILENT
    }
}
