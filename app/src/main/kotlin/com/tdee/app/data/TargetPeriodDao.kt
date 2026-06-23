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

    /**
     * Most recent target period for [userId], or null if none exists.
     * Ordered latest-first by startDate, with acceptedAt as the tie-break so that
     * two periods sharing a startDate (e.g. a same-day manual edit after a check-in)
     * resolve to the one accepted most recently.
     */
    @Query(
        "SELECT * FROM target_period WHERE userId = :userId " +
            "ORDER BY startDate DESC, acceptedAt DESC LIMIT 1"
    )
    suspend fun getLatest(userId: String): TargetPeriodEntity?

    @Query("DELETE FROM target_period WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM target_period WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    /** Delete all rows across all users — for test teardown only. */
    @Query("DELETE FROM target_period")
    suspend fun deleteAll()
}
