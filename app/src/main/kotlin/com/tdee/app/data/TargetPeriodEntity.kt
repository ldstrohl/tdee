package com.tdee.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "target_period")
data class TargetPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val tdeeAtCheckin: Double,
    val calorieTarget: Double,
    val proteinTargetG: Double,
    val fatTargetG: Double,
    val carbTargetG: Double,
    val acceptedAt: Instant,
)
