package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.LocalDate

@Dao
interface WeightTrendCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WeightTrendCacheEntity)

    /** Batch upsert; Room runs the whole list in a single transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<WeightTrendCacheEntity>)

    @Query("SELECT * FROM weight_trend_cache WHERE userId = :userId ORDER BY date ASC")
    suspend fun getAll(userId: String): List<WeightTrendCacheEntity>

    @Query("SELECT * FROM weight_trend_cache WHERE userId = :userId AND date = :date LIMIT 1")
    suspend fun getByDate(userId: String, date: LocalDate): WeightTrendCacheEntity?

    @Query("DELETE FROM weight_trend_cache WHERE userId = :userId AND date = :date")
    suspend fun deleteByDate(userId: String, date: LocalDate)

    @Query("DELETE FROM weight_trend_cache WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    /** Delete all rows across all users — for test teardown only. */
    @Query("DELETE FROM weight_trend_cache")
    suspend fun deleteAll()
}
