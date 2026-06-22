package com.tdee.app.data

import androidx.room.Entity
import java.time.LocalDate

/**
 * Composite primary key: (userId, date). Each user has an independent cache timeline.
 */
@Entity(
    tableName = "weight_trend_cache",
    primaryKeys = ["userId", "date"],
)
data class WeightTrendCacheEntity(
    val userId: String,
    val date: LocalDate,
    val emaKg: Double,
    val tdeeEstimate: Double,
    val tdeeMethod: TdeeMethodDb,
    val uncertaintyKcal: Double,
    val calibrating: Boolean,
)
