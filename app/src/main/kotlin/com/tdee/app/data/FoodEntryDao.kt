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

    /** All rows for the user including soft-deleted; primarily for debugging/export. */
    @Query("SELECT * FROM food_entry WHERE userId = :userId ORDER BY timestamp ASC")
    suspend fun getAll(userId: String): List<FoodEntryEntity>

    /** Non-deleted entries for the user only — used for intake mapping. */
    @Query("SELECT * FROM food_entry WHERE userId = :userId AND deletedAt IS NULL ORDER BY timestamp ASC")
    suspend fun getActive(userId: String): List<FoodEntryEntity>

    /** Soft-delete by setting deletedAt. */
    @Query("UPDATE food_entry SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Instant)

    @Query("DELETE FROM food_entry WHERE id = :id")
    suspend fun hardDeleteById(id: Long)

    @Query("DELETE FROM food_entry WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    /** Delete all rows across all users — for test teardown only. */
    @Query("DELETE FROM food_entry")
    suspend fun deleteAll()
}
