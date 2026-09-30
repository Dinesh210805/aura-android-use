package com.aura.aura_ui.contacts

/**
 * A single scored candidate returned by the 5-stage ContactResolver pipeline.
 *
 * @param contactId     Room primary key (from aura_contacts)
 * @param displayName   Full display name as stored in device contacts
 * @param phoneNumber   Primary phone number (first in list)
 * @param photoUri      Optional URI for avatar photo
 * @param score         0.0–1.0 confidence score
 * @param matchStage    Which pipeline stage produced the winning score
 */
data class ContactMatch(
    val contactId: String,
    val displayName: String,
    val phoneNumber: String,
    val photoUri: String? = null,
    val score: Float,
    val matchStage: MatchStage
)

/** Which stage of the 5-stage pipeline produced this match. */
enum class MatchStage {
    EXACT,
    PREFIX,
    LEVENSHTEIN,
    PHONETIC,
    TOKEN_SORT
}

/** Outcome of [ContactResolver.resolve]. */
sealed class ResolveResult {

    /**
     * Single high-confidence match (score > 0.92).
     * Agent proceeds silently — no HITL needed.
     */
    data class AutoResolve(val match: ContactMatch) : ResolveResult()

    /**
     * 1–4 plausible candidates (top score > 0.70).
     * Agent must emit a [HITLSignal.ContactDisambiguation].
     */
    data class Disambiguate(val candidates: List<ContactMatch>) : ResolveResult()

    /**
     * No candidates above 0.70, or no candidates at all.
     * Agent must emit a [HITLSignal.ContactDisambiguation] with MANUAL_ENTRY mode.
     */
    object ManualEntry : ResolveResult()
}

/** Passed inside HITLSignal.ContactDisambiguation to describe the resolver's outcome. */
enum class ResolveMode { DISAMBIGUATE, MANUAL_ENTRY }
