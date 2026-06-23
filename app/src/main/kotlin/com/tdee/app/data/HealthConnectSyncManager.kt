package com.tdee.app.data

import java.time.Clock
import java.time.Instant

/**
 * Orchestrates a Health Connect → Room sync for weight entries.
 *
 * Dedup contract
 * --------------
 * Each [HcWeight.uid] is stored as [WeightEntryEntity.healthConnectUid]. The DB schema
 * carries a UNIQUE index on (userId, healthConnectUid) ([idx_weight_user_hc_uid]).
 * Inserts use [WeightEntryDao.insertIgnoreDuplicate] (ON CONFLICT IGNORE), so:
 *   - re-syncing a record that already exists is a no-op (returns -1, not counted).
 *   - syncing a superset of previous records inserts only the new ones.
 *   - records from other users are never touched.
 *
 * @param source       Source seam; swap with [FakeHealthConnectSource] in tests.
 * @param weightDao    Room DAO for weight_entry table.
 * @param currentUser  Provides the active user id.
 * @param clock        Source of "now" for [WeightEntryEntity.createdAt]; use [Clock.fixed]
 *                     in tests for determinism.
 */
class HealthConnectSyncManager(
    private val source: HealthConnectSource,
    private val weightDao: WeightEntryDao,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {

    /**
     * Pull weight records from Health Connect and insert new ones into Room.
     *
     * @param fullHistory  When true, reads all available HC history (since = null).
     *                     This is the weight-history pre-seed path, called once after
     *                     the user grants permission. When false, reads only records
     *                     at or after the most recent existing HEALTH_CONNECT entry's
     *                     timestamp for the current user (incremental sync).
     *
     * @return The count of rows actually inserted (duplicates don't count).
     */
    suspend fun sync(fullHistory: Boolean): Int {
        val userId = currentUser.userId()

        val since: Instant? = if (fullHistory) {
            null
        } else {
            // Find the most recent HC entry already stored for this user.
            // getAll returns rows ordered by timestamp ASC, so the last entry is the latest.
            weightDao.getAll(userId)
                .lastOrNull { it.source == WeightSource.HEALTH_CONNECT }
                ?.timestamp
        }

        val records = source.readWeights(since)
        val now = clock.instant()

        var inserted = 0
        for (hcWeight in records) {
            val entity = WeightEntryEntity(
                userId = userId,
                timestamp = hcWeight.time,
                weightKg = hcWeight.weightKg,
                bodyFatPct = hcWeight.bodyFatPct,
                source = WeightSource.HEALTH_CONNECT,
                healthConnectUid = hcWeight.uid,
                createdAt = now,
            )
            val rowId = weightDao.insertIgnoreDuplicate(entity)
            if (rowId != -1L) inserted++
        }

        return inserted
    }
}
