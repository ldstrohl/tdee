package com.tdee.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedMealDao {

    @Insert
    suspend fun insert(m: SavedMealEntity): Long

    @Query("SELECT * FROM saved_meal WHERE userId = :userId ORDER BY createdAt DESC, id DESC")
    fun observeForUser(userId: String): Flow<List<SavedMealEntity>>

    @Query("SELECT * FROM saved_meal WHERE userId = :userId ORDER BY createdAt DESC, id DESC")
    suspend fun getForUser(userId: String): List<SavedMealEntity>

    @Query("SELECT * FROM saved_meal WHERE id = :id")
    suspend fun getById(id: Long): SavedMealEntity?

    @Query("DELETE FROM saved_meal WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM saved_meal WHERE userId = :userId")
    suspend fun deleteAll(userId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(meals: List<SavedMealEntity>): List<Long>
}
