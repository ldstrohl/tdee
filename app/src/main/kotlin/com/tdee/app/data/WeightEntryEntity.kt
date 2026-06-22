package com.tdee.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "weight_entry",
    indices = [
        Index(value = ["userId"]),
        // Dedup is per-user: the same Health Connect reading cannot appear twice for the same user.
        Index(value = ["userId", "healthConnectUid"], unique = true, name = "idx_weight_user_hc_uid"),
    ],
)
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val timestamp: Instant,
    val weightKg: Double,
    val bodyFatPct: Double? = null,
    val source: WeightSource,
    val healthConnectUid: String? = null,
    val createdAt: Instant,
)
