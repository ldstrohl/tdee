package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.time.Instant

@Dao
interface FoodEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FoodEntryEntity): Long

    @Update
    suspend fun update(entry: FoodEntryEntity)

    /** All rows including soft-deleted; primarily for debugging/export. */
    @Query("SELECT * FROM food_entry ORDER BY timestamp ASC")
    suspend fun getAll(): List<FoodEntryEntity>

    /** Non-deleted entries only — used for intake mapping. */
    @Query("SELECT * FROM food_entry WHERE deletedAt IS NULL ORDER BY timestamp ASC")
    suspend fun getActive(): List<FoodEntryEntity>

    /** Soft-delete by setting deletedAt. */
    @Query("UPDATE food_entry SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Instant)

    @Query("DELETE FROM food_entry WHERE id = :id")
    suspend fun hardDeleteById(id: Long)

    @Query("DELETE FROM food_entry")
    suspend fun deleteAll()
}
