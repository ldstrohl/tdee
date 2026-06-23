package com.tdee.app.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord

/**
 * The Health Connect permission set this app requests.
 *
 * Currently READ_WEIGHT only — [com.tdee.app.data.RealHealthConnectSource] reads
 * [WeightRecord]s and always returns null body fat, so no BodyFat read is needed.
 *
 * Used both by the permission-request contract (Settings) and as the source of
 * truth that the manifest's `@array/health_permissions` must mirror.
 */
val HEALTH_CONNECT_PERMISSIONS: Set<String> = setOf(
    HealthPermission.getReadPermission(WeightRecord::class),
)
