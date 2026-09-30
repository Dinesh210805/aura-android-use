package com.aura.aura_ui.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a synced device contact.
 *
 * `phonetic_key` stores the primary Double Metaphone code of the display name's
 * first token — e.g. "Elakiya" → "ALK". This lets ContactResolver skip Stage 4
 * for already-indexed contacts by doing a simple DB query instead of re-computing.
 *
 * `aliases` stores STT variants that have been learned via the correction loop
 * (Task 5): after 5 confirmed corrections, the STT spelling is appended here so
 * Stage 1 exact-match can catch it before fuzzy stages even run.
 */
@Entity(
    tableName = "aura_contacts",
    indices = [
        Index(value = ["phoneticKey"]),
        Index(value = ["phoneticKeyAlt"])
    ]
)
data class AuraContactEntity(
    @PrimaryKey val contactId: String,
    val displayName: String,
    /** JSON-encoded List<String> of phone numbers */
    val phoneNumbers: String = "[]",
    /** JSON-encoded List<String> of email addresses */
    val emails: String = "[]",
    val photoUri: String? = null,
    /** Double Metaphone primary code of first name token */
    val phoneticKey: String = "",
    /** Double Metaphone alternate code of first name token */
    val phoneticKeyAlt: String = "",
    /** JSON-encoded List<String> of confirmed STT spelling variants */
    val aliases: String = "[]",
    val lastSyncedAt: Long = System.currentTimeMillis()
)
