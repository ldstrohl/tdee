package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.Instant

@Dao
interface WeightEntryDao {

    /** Insert, replacing on id conflict. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WeightEntryEntity): Long

    /** Batch insert; Room runs the whole list in a single transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<WeightEntryEntity>): List<Long>

    /** Timestamp of the most recent weight entry for [userId], or null if none exist. */
    @Query("SELECT MAX(timestamp) FROM weight_entry WHERE userId = :userId")
    suspend fun getLatestTimestamp(userId: String): Instant?

    /**
     * Insert ignoring duplicates by (userId, healthConnectUid) composite index.
     * Entries with null healthConnectUid are always inserted.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreDuplicate(entry: WeightEntryEntity): Long

    @Query("SELECT * FROM weight_entry WHERE userId = :userId ORDER BY timestamp ASC")
    suspend fun getAll(userId: String): List<WeightEntryEntity>

    @Query("DELETE FROM weight_entry WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM weight_entry WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    /** Delete all rows across all users — for test teardown only. */
    @Query("DELETE FROM weight_entry")
    suspend fun deleteAll()
}
