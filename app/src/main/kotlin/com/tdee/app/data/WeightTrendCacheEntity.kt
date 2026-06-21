package com.tdee.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "weight_trend_cache")
data class WeightTrendCacheEntity(
    @PrimaryKey val date: LocalDate,
    val emaKg: Double,
    val tdeeEstimate: Double,
    val tdeeMethod: TdeeMethodDb,
    val uncertaintyKcal: Double,
    val calibrating: Boolean,
)
