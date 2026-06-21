package com.tdee.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import java.time.Instant

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Long = 1L,
    val userId: String,
    val sex: Sex,
    val birthYear: Int,
    val heightCm: Double,
    val activityLevel: ActivityLevel,
    val goalRateKgPerWeek: Double,
    val goalWeightKg: Double? = null,
    val proteinGPerKg: Double = 2.0,
    val fatPctOfCalories: Double = 0.25,
    val dayStartHour: Int = 0,
    val smoothingWindowDays: Int = 14,
    val tdeeWindowDays: Int = 14,
    val createdAt: Instant,
    val updatedAt: Instant,
)
