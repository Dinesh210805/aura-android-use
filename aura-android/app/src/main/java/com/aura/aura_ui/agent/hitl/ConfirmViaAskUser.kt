package com.aura.aura_ui.agent.hitl

/**
 * T12 — bridges the hook chain's Confirm decisions to the ask_user HITL plane
 * (chips in the overlay, spoken on the Live plane — whatever UI observes the
 * broker). Yes-only opt-in: timeout, dismissal, and a busy broker all read as
 * "no" — a destructive action never proceeds on silence.
 */
fun confirmViaAskUser(broker: AskUserBroker, timeoutMs: Long = 60_000L): suspend (String) -> Boolean = { reason ->
    when (val answer = broker.ask(reason, listOf("Yes", "No"), timeoutMs)) {
        is AskUserAnswer.Answered -> answer.text.trim().equals("yes", ignoreCase = true)
        else -> false
    }
}
