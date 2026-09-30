package com.aura.aura_ui.agent.ledger

/**
 * Pure index logic for per-turn ledger injection (the strategy node maps Koog messages to
 * their user-text and back, same shape as PerceptionEvictionPolicy): every prior RUN LEDGER
 * user-message is stale the moment a new one is rendered — drop them all, append fresh.
 */
object LedgerInjection {
    /** Indices of user messages that ARE a rendered ledger block (identified by the marker prefix). */
    fun staleLedgerIndices(userTexts: List<String?>): Set<Int> =
        userTexts.withIndex()
            .filter { (_, text) -> text?.startsWith(RunLedgerRenderer.MARKER) == true }
            .map { it.index }
            .toSet()
}
