package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TargetPeriodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(period: TargetPeriodEntity): Long

    @Query("SELECT * FROM target_period ORDER BY startDate ASC")
    suspend fun getAll(): List<TargetPeriodEntity>

    @Query("DELETE FROM target_period WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM target_period")
    suspend fun deleteAll()
}
