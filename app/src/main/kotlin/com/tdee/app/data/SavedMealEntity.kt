package com.tdee.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "saved_meal", indices = [Index(value = ["userId"])])
data class SavedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val name: String,
    val items: List<SavedMealItem>,
    val createdAt: Instant,
)

data class SavedMealItem(
    val name: String,
    val kcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbG: Double,
    val grams: Double?,
)
