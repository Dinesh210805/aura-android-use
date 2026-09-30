package com.aura.aura_ui.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aura.aura_ui.data.database.entities.SttCorrectionEntity

@Dao
interface SttCorrectionDao {

    @Query("""
        SELECT * FROM stt_corrections
        WHERE sttHeard = :sttHeard AND resolvedContactId = :contactId
        LIMIT 1
    """)
    suspend fun find(sttHeard: String, contactId: String): SttCorrectionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(correction: SttCorrectionEntity): Long

    @Query("""
        UPDATE stt_corrections
        SET correctionCount = correctionCount + 1,
            lastCorrectedAt = :timestamp
        WHERE sttHeard = :sttHeard AND resolvedContactId = :contactId
    """)
    suspend fun increment(sttHeard: String, contactId: String, timestamp: Long = System.currentTimeMillis())

    /** Returns all corrections that have reached the alias promotion threshold. */
    @Query("""
        SELECT * FROM stt_corrections
        WHERE correctionCount >= ${SttCorrectionEntity.ALIAS_THRESHOLD}
    """)
    suspend fun getReadyForPromotion(): List<SttCorrectionEntity>
}
