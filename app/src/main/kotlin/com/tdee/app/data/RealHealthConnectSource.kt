package com.tdee.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Production implementation of [HealthConnectSource] backed by [HealthConnectClient].
 *
 * Uses `androidx.health.connect:connect-client:1.0.0-alpha11`, the latest version
 * compatible with compileSdk 34 / AGP 8.5.2.
 *
 * DEVICE-VERIFICATION-PENDING: All runtime behaviour of this class must be
 * verified on a physical device or emulator with Health Connect installed,
 * because [HealthConnectClient] APIs are no-ops / throw in unit-test JVM.
 *
 * Known pending items:
 *  1. Body fat: [readWeights] always returns bodyFatPct = null. A companion
 *     BodyFatRecord read keyed by timestamp (±some window) would be required
 *     to correlate body-fat readings with weight readings; deferred until
 *     on-device verification is available.
 *  2. Permission check: [hasReadPermission] uses
 *     [HealthConnectClient.getPermissionController().getGrantedPermissions()]
 *     which requires a live HealthConnectClient; the call may throw in test
 *     environments (tests use [FakeHealthConnectSource] instead).
 *  3. [isAvailable]: uses [HealthConnectClient.sdkStatus], whose return codes
 *     (SDK_AVAILABLE, SDK_UNAVAILABLE, etc.) should be validated against real
 *     device behaviour.
 *  4. Pagination: readRecords returns a single page. For large HC histories a
 *     follow-up task should add page-token looping.
 *
 * @param context Application context (only applicationContext is retained internally).
 */
class RealHealthConnectSource(context: Context) : HealthConnectSource {

    private val appContext = context.applicationContext

    // Lazily create the client; will throw on JVM unit tests (that's expected —
    // tests use FakeHealthConnectSource).
    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(appContext)
    }

    // DEVICE-VERIFICATION-PENDING: SDK_AVAILABLE = 3 in the SDK constants.
    // connect-client 1.1.0 exposes this as the function getSdkStatus(context)
    // (the `sdkStatus` property accessor from alpha11 was removed).
    override suspend fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE

    // DEVICE-VERIFICATION-PENDING: getGrantedPermissions returns Set<String>;
    // each entry is the string permission name (e.g. "android.permission.health.READ_WEIGHT").
    override suspend fun hasReadPermission(): Boolean {
        val granted: Set<String> = client.permissionController.getGrantedPermissions()
        return HealthPermission.getReadPermission(WeightRecord::class) in granted
    }

    /**
     * Read [WeightRecord]s from Health Connect.
     *
     * When [since] is null, an open-ended filter starting from [Instant.EPOCH] is used to
     * pull all available history. When [since] is provided, only records at or after that
     * instant are returned (inclusive boundary per [TimeRangeFilter.after] semantics —
     * DEVICE-VERIFICATION-PENDING).
     */
    override suspend fun readWeights(since: Instant?): List<HcWeight> {
        val timeFilter = if (since == null) {
            TimeRangeFilter.after(Instant.EPOCH)
        } else {
            TimeRangeFilter.after(since)
        }

        val request = ReadRecordsRequest(
            recordType = WeightRecord::class,
            timeRangeFilter = timeFilter,
        )

        val response = client.readRecords(request)

        return response.records.map { record ->
            HcWeight(
                uid = record.metadata.id,
                time = record.time,
                weightKg = record.weight.inKilograms,
                // DEVICE-VERIFICATION-PENDING: body fat always null — see class KDoc.
                bodyFatPct = null,
            )
        }
    }

    /**
     * DEBUG-ONLY test-data writer.
     *
     * Inserts [count] [WeightRecord]s into Health Connect, backdated one per day
     * over the past [count] days (today, yesterday, …) with a gentle downward
     * trend starting at 84.0 kg and dropping 0.2 kg per day going back in time —
     * i.e. the most recent day is the lightest. Each record is a manual entry
     * with a proper [Metadata], an `Instant` measurement [WeightRecord.time], the
     * device's current [java.time.ZoneOffset], and [Mass.kilograms].
     *
     * Used only by the debug Settings button to seed an otherwise-empty emulator
     * Health Connect so the read/import path can be verified. Requires the
     * WRITE_WEIGHT permission (granted via the debug-inclusive Connect request).
     *
     * @return the number of records inserted.
     */
    suspend fun writeSampleWeights(count: Int = 8): Int {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val zone = ZoneId.systemDefault()

        val records = (0 until count).map { daysAgo ->
            val time = now.minus(daysAgo.toLong(), ChronoUnit.DAYS)
            val zoneOffset = zone.rules.getOffset(time)
            val weightKg = 84.0 - daysAgo * 0.2
            WeightRecord(
                time = time,
                zoneOffset = zoneOffset,
                weight = Mass.kilograms(weightKg),
                metadata = Metadata.manualEntry(),
            )
        }

        client.insertRecords(records)
        return records.size
    }
}
