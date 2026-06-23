package com.tdee.app.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tdee.app.TdeeApplication
import com.tdee.app.data.HealthConnectSyncManager
import java.util.concurrent.TimeUnit

/**
 * Periodic incremental Health Connect sync.
 *
 * Pulls new weight records (`fullHistory = false`) on WorkManager's schedule. The
 * actual sync logic lives in [HealthConnectSyncManager]; this worker only delegates
 * and translates the outcome into a [Result]. Resilient by design: if HC is
 * unavailable or permission was revoked, [runSync] catches the failure and returns
 * a non-crashing result.
 */
class HealthConnectSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val syncManager = (applicationContext as TdeeApplication).container.healthConnectSyncManager
        return runSync(syncManager)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "health_connect_periodic_sync"
        private const val REPEAT_INTERVAL_HOURS = 6L

        /**
         * Delegates one incremental sync to [syncManager], returning [Result.success]
         * on completion and [Result.retry] if it threw (e.g. transient HC unavailability).
         * Pure of WorkManager scheduling so it can be unit-tested with a fake source.
         */
        suspend fun runSync(syncManager: HealthConnectSyncManager): Result =
            try {
                syncManager.sync(fullHistory = false)
                Result.success()
            } catch (t: Throwable) {
                Result.retry()
            }

        /**
         * Enqueue the unique periodic sync. KEEP policy means an existing schedule is
         * left intact, so calling this on every connect/app-open is idempotent.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
