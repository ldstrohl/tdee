package com.tdee.app.data

import java.time.Instant

// ---------------------------------------------------------------------------
// Seam — plain data type returned by the source (no HC SDK types exposed)
// ---------------------------------------------------------------------------

/**
 * A single weight record read from Health Connect.
 *
 * This is a plain data class with no dependency on the HC SDK so that
 * [HealthConnectSyncManager] and its tests are fully JVM-runnable with a fake.
 *
 * @param uid         Health Connect record id (metadata.id).
 * @param time        Instant the measurement was taken (WeightRecord.time).
 * @param weightKg    Body weight in kilograms (WeightRecord.weight.inKilograms).
 * @param bodyFatPct  Body-fat percentage if available; null otherwise.
 *                    DEVICE-VERIFICATION-PENDING: body fat comes from a separate
 *                    BodyFatRecord read; see [RealHealthConnectSource] comments.
 */
data class HcWeight(
    val uid: String,
    val time: Instant,
    val weightKg: Double,
    val bodyFatPct: Double?,
)

// ---------------------------------------------------------------------------
// Seam interface
// ---------------------------------------------------------------------------

/**
 * Source seam for Health Connect reads.
 *
 * The real implementation wraps [androidx.health.connect.client.HealthConnectClient];
 * tests use [FakeHealthConnectSource] to avoid HC SDK / device dependencies.
 */
interface HealthConnectSource {
    /**
     * Returns true when Health Connect is installed and supported on this device.
     * Safe to call without a permission grant.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Returns true when the app holds the READ_WEIGHT permission.
     * Safe to call without requiring prior [isAvailable] check.
     */
    suspend fun hasReadPermission(): Boolean

    /**
     * Read weight records from Health Connect.
     *
     * @param since  When null, reads all available history (pre-seed pull).
     *               When non-null, reads records whose time is ≥ [since] (incremental sync).
     * @return       List of [HcWeight] ordered by time ascending; empty if none.
     */
    suspend fun readWeights(since: Instant?): List<HcWeight>
}
