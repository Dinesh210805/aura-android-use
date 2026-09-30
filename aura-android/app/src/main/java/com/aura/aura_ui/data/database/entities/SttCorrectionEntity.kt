package com.aura.aura_ui.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks STT → contact resolution corrections made by the user via the
 * ContactDisambiguationSheet. Once a (sttHeard, contactId) pair accumulates
 * [ALIAS_THRESHOLD] corrections, ContactSyncService promotes `sttHeard` into
 * that contact's `aliases` column, making future Stage 1 exact matches instant.
 */
@Entity(
    tableName = "stt_corrections",
    indices = [Index(value = ["sttHeard", "resolvedContactId"], unique = true)]
)
data class SttCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sttHeard: String,
    val resolvedContactId: String,
    val correctionCount: Int = 1,
    val lastCorrectedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** Number of confirmations required before promoting to alias. */
        const val ALIAS_THRESHOLD = 5
    }
}
