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

    @Query("SELECT * FROM weight_trend_cache ORDER BY date ASC")
    suspend fun getAll(): List<WeightTrendCacheEntity>

    @Query("SELECT * FROM weight_trend_cache WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: LocalDate): WeightTrendCacheEntity?

    @Query("DELETE FROM weight_trend_cache WHERE date = :date")
    suspend fun deleteByDate(date: LocalDate)

    @Query("DELETE FROM weight_trend_cache")
    suspend fun deleteAll()
}
