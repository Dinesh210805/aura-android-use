package com.aura.aura_ui.services

/**
 * The discrete states surfaced by the AURA status-bar chip.
 *
 * Single source of truth for the verb shown in the Promoted Ongoing
 * notification (status-bar pill on Android 16+, shade-only notification
 * on older releases).
 *
 * **This type describes ACTIVITY — what the agent is doing right now — and
 * nothing else.** It deliberately carries no error or health state:
 *
 *  - A failed *tool call* is not a state. Errors are ordinary in an agent
 *    loop and the agent retries; see [StatusToolPhaseSink].
 *  - MCP **server health** (stopped / starting / crashed) is infrastructure,
 *    not activity. A booting server is not the agent being "stuck". Health
 *    has its own surface (the MCP screen).
 *
 * The removed `Stuck` state violated both rules at once and, being sticky by
 * design, wedged the chip permanently after any recoverable hiccup.
 *
 * Design notes:
 *  - Each verb fits comfortably under the ~12-character practical limit
 *    of the status-bar chip slot next to a punch-hole camera.
 *  - No animation, no progress arithmetic — re-posting only happens on a
 *    real state transition (event-driven cadence).
 */
sealed interface AuraStatus {
    /** Text shown in the status-bar pill via setShortCriticalText. */
    val verb: String

    data object Idle : AuraStatus { override val verb = "Ready" }
    data object Listening : AuraStatus { override val verb = "Listening" }
    data object Thinking : AuraStatus { override val verb = "Thinking" }
    data object Acting : AuraStatus { override val verb = "Acting" }
    data object Speaking : AuraStatus { override val verb = "Speaking" }
    data object Verifying : AuraStatus { override val verb = "Verifying" }
}
