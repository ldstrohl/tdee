package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WeightEntryDao {

    /** Insert, replacing on id conflict. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WeightEntryEntity): Long

    /**
     * Insert ignoring duplicates by healthConnectUid (unique index).
     * Entries with null healthConnectUid are always inserted.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreDuplicate(entry: WeightEntryEntity): Long

    @Query("SELECT * FROM weight_entry ORDER BY timestamp ASC")
    suspend fun getAll(): List<WeightEntryEntity>

    @Query("DELETE FROM weight_entry WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM weight_entry")
    suspend fun deleteAll()
}
