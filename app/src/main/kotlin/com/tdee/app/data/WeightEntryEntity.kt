package com.tdee.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "weight_entry",
    indices = [Index(value = ["healthConnectUid"], unique = true, name = "idx_weight_hc_uid")],
)
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val weightKg: Double,
    val bodyFatPct: Double? = null,
    val source: WeightSource,
    val healthConnectUid: String? = null,
    val createdAt: Instant,
)
