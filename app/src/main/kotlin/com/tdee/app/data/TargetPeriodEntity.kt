package com.tdee.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "target_period",
    indices = [Index(value = ["userId"])],
)
data class TargetPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val tdeeAtCheckin: Double,
    val calorieTarget: Double,
    val proteinTargetG: Double,
    val fatTargetG: Double,
    val carbTargetG: Double,
    val acceptedAt: Instant,
)
