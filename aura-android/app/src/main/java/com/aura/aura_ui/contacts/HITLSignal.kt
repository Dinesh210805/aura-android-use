package com.aura.aura_ui.contacts

import java.util.UUID

/**
 * Client-side Human-in-the-Loop signal bus.
 *
 * Agents emit a [HITLSignal] subclass when they need user input.
 * They do NOT render UI — they suspend until a callback fires.
 * [HITLBroker] owns all presentation decisions.
 *
 * CORRECT mental model:
 *   agent → emit HITLSignal → HITLBroker → render sheet → callback → agent resumes
 */
sealed class HITLSignal {

    abstract val requestId: String

    // ─────────────────────────────────────────────────────────────────────────
    // Contact disambiguation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emitted when ContactResolver returns DISAMBIGUATE or MANUAL_ENTRY.
     *
     * @param sttHeard      The raw STT string (e.g. "ilakya")
     * @param originalCommand Full utterance (e.g. "call ilakya")
     * @param candidates    Ranked list of up to 4 ContactMatch entries
     * @param resolveMode   DISAMBIGUATE (candidates present) or MANUAL_ENTRY (none)
     * @param onResolved    Invoked with the confirmed contactId when user picks
     * @param onCancelled   Invoked when user taps Skip / dismisses the sheet
     */
    data class ContactDisambiguation(
        override val requestId: String = UUID.randomUUID().toString(),
        val sttHeard: String,
        val originalCommand: String,
        val candidates: List<ContactMatch>,
        val resolveMode: ResolveMode,
        val onResolved: (contactId: String) -> Unit,
        val onCancelled: () -> Unit
    ) : HITLSignal()

    // ─────────────────────────────────────────────────────────────────────────
    // Permission required
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emitted when READ_CONTACTS is denied. Broker shows a Settings deeplink prompt.
     *
     * @param permissionName  Android permission string (e.g. READ_CONTACTS)
     * @param rationale       Human-readable reason shown in the prompt
     * @param onOpenSettings  Invoked when user taps "Open Settings"
     * @param onCancelled     Invoked when user dismisses without granting
     */
    data class PermissionRequired(
        override val requestId: String = UUID.randomUUID().toString(),
        val permissionName: String,
        val rationale: String,
        val onOpenSettings: () -> Unit,
        val onCancelled: () -> Unit
    ) : HITLSignal()
}
