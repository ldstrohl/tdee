package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface UserProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)

    @Query("SELECT * FROM user_profile WHERE userId = :userId LIMIT 1")
    suspend fun get(userId: String): UserProfileEntity?

    @Update
    suspend fun update(profile: UserProfileEntity)

    @Query("DELETE FROM user_profile WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("DELETE FROM user_profile")
    suspend fun deleteAll()
}
