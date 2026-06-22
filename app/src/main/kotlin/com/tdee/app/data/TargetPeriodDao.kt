package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TargetPeriodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(period: TargetPeriodEntity): Long

    @Query("SELECT * FROM target_period WHERE userId = :userId ORDER BY startDate ASC")
    suspend fun getAll(userId: String): List<TargetPeriodEntity>

    @Query("DELETE FROM target_period WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM target_period WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    /** Delete all rows across all users — for test teardown only. */
    @Query("DELETE FROM target_period")
    suspend fun deleteAll()
}
