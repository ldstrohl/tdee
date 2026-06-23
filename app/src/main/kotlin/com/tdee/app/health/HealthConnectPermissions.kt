package com.tdee.app.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import com.tdee.app.BuildConfig

/**
 * The Health Connect permission set this app requests.
 *
 * Production is READ_WEIGHT only — [com.tdee.app.data.RealHealthConnectSource] reads
 * [WeightRecord]s and always returns null body fat, so no BodyFat read is needed.
 *
 * Used both by the permission-request contract (Settings) and as the source of
 * truth that the manifest's `@array/health_permissions` must mirror.
 *
 * On DEBUG builds the request set additionally includes WRITE_WEIGHT (see
 * [HEALTH_CONNECT_PERMISSIONS]) so a single Connect grant covers read + write,
 * letting the debug sample-weight writer insert test data. The write permission
 * is declared only in `app/src/debug/AndroidManifest.xml`, so release builds
 * neither declare nor request it.
 */
val HEALTH_CONNECT_READ_PERMISSIONS: Set<String> = setOf(
    HealthPermission.getReadPermission(WeightRecord::class),
)

/**
 * The permission set the request contract should launch with.
 *
 * Release: identical to [HEALTH_CONNECT_READ_PERMISSIONS] (read-only).
 * Debug:   also includes the WRITE_WEIGHT permission.
 */
val HEALTH_CONNECT_PERMISSIONS: Set<String> = if (BuildConfig.DEBUG) {
    HEALTH_CONNECT_READ_PERMISSIONS + HealthPermission.getWritePermission(WeightRecord::class)
} else {
    HEALTH_CONNECT_READ_PERMISSIONS
}
