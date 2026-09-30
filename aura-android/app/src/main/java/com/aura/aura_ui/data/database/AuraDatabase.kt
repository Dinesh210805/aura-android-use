package com.aura.aura_ui.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aura.aura_ui.data.database.dao.AuraContactDao
import com.aura.aura_ui.data.database.dao.SttCorrectionDao
import com.aura.aura_ui.data.database.entities.AuraContactEntity
import com.aura.aura_ui.data.database.entities.SttCorrectionEntity

/**
 * Central Room database for AURA.
 *
 * Bump [version] and add a [androidx.room.migration.Migration] whenever the schema changes.
 * Current tables:
 *  - aura_contacts   — synced device contacts with phonetic keys + STT aliases
 *  - stt_corrections — correction-count ledger driving the learning loop
 */
@Database(
    entities = [
        AuraContactEntity::class,
        SttCorrectionEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AuraDatabase : RoomDatabase() {

    abstract fun audioDao(): AudioDao
    abstract fun contactDao(): AuraContactDao
    abstract fun sttCorrectionDao(): SttCorrectionDao
}
