package com.aura.aura_ui.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aura.aura_ui.data.database.entities.AuraContactEntity

@Dao
interface AuraContactDao {

    @Query("SELECT * FROM aura_contacts ORDER BY displayName ASC")
    suspend fun getAllContacts(): List<AuraContactEntity>

    @Query("SELECT * FROM aura_contacts WHERE contactId = :id LIMIT 1")
    suspend fun getById(id: String): AuraContactEntity?

    @Query("SELECT * FROM aura_contacts WHERE phoneticKey = :code OR phoneticKeyAlt = :code")
    suspend fun getByPhoneticCode(code: String): List<AuraContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contacts: List<AuraContactEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: AuraContactEntity)

    @Update
    suspend fun update(contact: AuraContactEntity)

    @Query("DELETE FROM aura_contacts WHERE contactId NOT IN (:activeIds)")
    suspend fun deleteStale(activeIds: List<String>)

    @Query("DELETE FROM aura_contacts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM aura_contacts")
    suspend fun count(): Int
}
