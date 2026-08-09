package com.tdee.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "food_entry",
    indices = [Index(value = ["userId"])],
)
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val timestamp: Instant,
    val rawText: String,
    val name: String,
    val brand: String? = null,
    val quantity: Double,
    val unit: String,
    val grams: Double,
    val kcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbG: Double,
    val fdcId: String? = null,
    val sourceDb: FoodSourceDb,
    val mealId: String? = null,
    val mealName: String? = null,
    @ColumnInfo(defaultValue = "1.0") val scaleFactor: Double = 1.0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)
