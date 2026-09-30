package com.aura.mcp.bridge

/**
 * Port into the host app's contact-resolution facilities.
 *
 * Exists so the agent can turn a spoken/typed NAME ("call Mom", "WhatsApp
 * Dinesh") into a phone number deterministically — `system_intent` dial/SMS
 * and `wa.me` deep links all require a number, and gesture-navigating the
 * Contacts app for it is slow and fragile. The `:app` module binds this to the
 * 5-stage fuzzy resolver (exact → prefix → Levenshtein → phonetic → token-sort)
 * built for STT input.
 *
 * Privacy contract for implementations and callers:
 *  - resolution happens fully on-device;
 *  - the tool result is the ONLY place a number may appear — it must never be
 *    persisted to learnings (the tool is classified read-only in
 *    `PathStepSanitizer`) nor echoed back to the user unprompted.
 */
interface ContactsBridge {

    /**
     * Resolve [name] against the device contacts. Never throws — permission
     * problems surface as [ContactResolution.status] = `permission_denied`.
     */
    suspend fun resolveContact(name: String): ContactResolution
}

/**
 * Outcome of a [ContactsBridge.resolveContact] call.
 *
 * @param status `auto` (single high-confidence match — use it), `disambiguate`
 *   (2–4 plausible candidates — ask the user which), `none` (nothing above
 *   threshold — ask the user for the number), or `permission_denied`
 *   (READ_CONTACTS not granted — tell the user to grant Contacts access).
 * @param candidates scored matches, best first; empty for `none` / `permission_denied`
 */
data class ContactResolution(
    val status: String,
    val candidates: List<ContactCandidate>,
) {
    companion object {
        const val STATUS_AUTO = "auto"
        const val STATUS_DISAMBIGUATE = "disambiguate"
        const val STATUS_NONE = "none"
        const val STATUS_PERMISSION_DENIED = "permission_denied"
    }
}

/**
 * One scored contact match.
 *
 * @param contactId stable device contact id (opaque to the agent)
 * @param displayName full display name as stored on the device
 * @param phoneNumber primary phone number, ready for `system_intent` /
 *   `wa.me` templates
 * @param score resolver confidence, 0.0–1.0
 */
data class ContactCandidate(
    val contactId: String,
    val displayName: String,
    val phoneNumber: String,
    val score: Float,
)
