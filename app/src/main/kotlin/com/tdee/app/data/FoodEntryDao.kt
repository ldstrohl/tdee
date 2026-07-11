package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface FoodEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FoodEntryEntity): Long

    /** Batch insert; Room runs the whole list in a single transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<FoodEntryEntity>): List<Long>

    @Update
    suspend fun update(entry: FoodEntryEntity)

    /** All rows for the user including soft-deleted; primarily for debugging/export. */
    @Query("SELECT * FROM food_entry WHERE userId = :userId ORDER BY timestamp ASC")
    suspend fun getAll(userId: String): List<FoodEntryEntity>

    /** Non-deleted entries for the user only — used for intake mapping. */
    @Query("SELECT * FROM food_entry WHERE userId = :userId AND deletedAt IS NULL ORDER BY timestamp ASC")
    suspend fun getActive(userId: String): List<FoodEntryEntity>

    /**
     * Non-deleted entries for the user whose timestamp falls in [from, until).
     * Used by [TdeeRepository.todayFoodEntries] to limit the scan to the current log-day window.
     */
    @Query(
        "SELECT * FROM food_entry " +
        "WHERE userId = :userId AND deletedAt IS NULL " +
        "AND timestamp >= :from AND timestamp < :until " +
        "ORDER BY timestamp ASC"
    )
    suspend fun getActiveInRange(userId: String, from: Instant, until: Instant): List<FoodEntryEntity>

    /**
     * Reactive (Flow) variant of [getActiveInRange]. Room re-emits whenever any food_entry row
     * changes, so callers automatically see adds and soft-deletes without polling.
     *
     * The [from]/[until] window is computed at collection time by the caller — see
     * [TdeeRepository.observeTodayFoodEntries] for the exact boundary logic. This is
     * acceptable for MVP; no mid-session midnight-rollover handling is performed.
     */
    @Query(
        "SELECT * FROM food_entry " +
        "WHERE userId = :userId AND deletedAt IS NULL " +
        "AND timestamp >= :from AND timestamp < :until " +
        "ORDER BY timestamp ASC"
    )
    fun observeActiveInRange(userId: String, from: Instant, until: Instant): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entry WHERE id = :id")
    suspend fun getById(id: Long): FoodEntryEntity?

    /** Soft-delete by setting deletedAt. */
    @Query("UPDATE food_entry SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Instant)

    /** Soft-delete all entries belonging to the given meal group. */
    @Query("UPDATE food_entry SET deletedAt = :deletedAt WHERE userId = :userId AND mealId = :mealId")
    suspend fun softDeleteByMeal(userId: String, mealId: String, deletedAt: Instant)

    /** Rename a meal group — mealName is denormalized onto every row of the group. */
    @Query(
        "UPDATE food_entry SET mealName = :name, updatedAt = :updatedAt " +
        "WHERE userId = :userId AND mealId = :mealId"
    )
    suspend fun renameMeal(userId: String, mealId: String, name: String, updatedAt: Instant)

    @Query("DELETE FROM food_entry WHERE id = :id")
    suspend fun hardDeleteById(id: Long)

    @Query("DELETE FROM food_entry WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    /** Delete all rows across all users — for test teardown only. */
    @Query("DELETE FROM food_entry")
    suspend fun deleteAll()

    /** Non-deleted entries belonging to the given meal group for this user. */
    @Query(
        "SELECT * FROM food_entry " +
        "WHERE userId = :userId AND mealId = :mealId AND deletedAt IS NULL " +
        "ORDER BY timestamp ASC"
    )
    suspend fun getByMeal(userId: String, mealId: String): List<FoodEntryEntity>

    /** Reactive mirror of [getByMeal]. */
    @Query(
        "SELECT * FROM food_entry " +
        "WHERE userId = :userId AND mealId = :mealId AND deletedAt IS NULL " +
        "ORDER BY timestamp ASC"
    )
    fun observeByMeal(userId: String, mealId: String): Flow<List<FoodEntryEntity>>
}
