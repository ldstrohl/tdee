package com.tdee.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tdee.app.TdeeApplication
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Periodic daily backup to Google Drive.
 *
 * Pulls credentials, ensures the backup folder exists, creates a timestamped JSON backup,
 * uploads it, and prunes old backups. The actual backup/upload/prune logic lives in
 * [BackupManager] and [DriveClient]; this worker only delegates and translates the
 * outcome into a [Result]. Failures to authorize are treated as non-retryable (requires
 * user action to reconnect); transient network/server errors retry.
 */
class DriveBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val backupManager = (applicationContext as TdeeApplication).container.backupManager
        val driveClient = (applicationContext as TdeeApplication).container.driveClient
        val driveAuth = (applicationContext as TdeeApplication).container.driveAuth

        return runBackup(
            localCounts = { backupManager.localCounts() },
            backup = { backupManager.backup() },
            ensureFolder = { driveClient.ensureFolder() },
            upload = { folderId, filename, json -> driveClient.upload(folderId, filename, json) },
            prune = { folderId -> driveClient.prune(folderId, 10) },
            setNeedsReconnect = { driveAuth.needsReconnect = it },
            clock = Clock.systemUTC(),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "drive_backup_periodic"
        private const val REPEAT_INTERVAL_HOURS = 24L

        /**
         * Delegates one backup cycle to the provided functions, returning [Result.success]
         * on completion, [Result.failure] if authorization is revoked (requires user action),
         * and [Result.retry] if a transient error (network/server/unknown) occurred.
         * Pure of WorkManager scheduling so it can be unit-tested with fakes.
         */
        suspend fun runBackup(
            localCounts: suspend () -> BackupCounts,
            backup: suspend () -> String,
            ensureFolder: suspend () -> String,
            upload: suspend (String, String, String) -> Unit,
            prune: suspend (String) -> Unit,
            setNeedsReconnect: (Boolean) -> Unit,
            clock: Clock = Clock.systemUTC(),
        ): Result =
            try {
                // Never auto-upload an empty device. After a reinstall the app is empty until the
                // user restores, and an unattended daily backup would otherwise upload that empty
                // state — then, since retention keeps only the newest N, repeated empty uploads
                // would prune away every good backup the user still needs. Skipping is reported as
                // success: there is genuinely nothing to do, and retrying would not change that.
                // The manual "Back up now" button is deliberately not gated — that is an explicit
                // user action on data they can see.
                if (localCounts().isEmpty()) {
                    Result.success()
                } else {
                    val json = backup()
                    val folderId = ensureFolder()
                    val filename = "tdee-backup-${clock.instant().toString().replace(':', '-')}.json"
                    upload(folderId, filename, json)
                    prune(folderId)
                    Result.success()
                }
            } catch (e: DriveException) {
                when (e.error) {
                    DriveError.NeedsAuth -> {
                        // Auth was revoked; retrying without user action is hopeless and wastes battery.
                        setNeedsReconnect(true)
                        Result.failure()
                    }
                    DriveError.Network, DriveError.RateLimited, DriveError.Server -> Result.retry()
                    is DriveError.Unknown -> Result.retry()
                }
            } catch (e: NeedsAuthorizationException) {
                // Consent missing; retrying without user action is hopeless and wastes battery.
                setNeedsReconnect(true)
                Result.failure()
            } catch (t: Throwable) {
                Result.retry()
            }

        /**
         * Enqueue the unique periodic backup. KEEP policy means an existing schedule is
         * left intact, so calling this on every connection is idempotent.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
            ).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
