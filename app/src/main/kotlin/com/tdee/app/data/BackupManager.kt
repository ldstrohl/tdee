package com.tdee.app.data

import androidx.room.withTransaction
import com.tdee.app.ui.theme.ThemePreference
import com.tdee.app.ui.theme.ThemeStore
import java.io.File
import java.time.Clock

/** Row counts for the restore-confirmation dialog and the [BackupManager.restore] result. */
data class BackupCounts(
    val foods: Int,
    val weights: Int,
    val savedMeals: Int,
    val targets: Int,
    val hasProfile: Boolean,
)

data class RestoreResult(val counts: BackupCounts, val snapshotPath: String)

private fun BackupData.counts() = BackupCounts(
    foods = foods.size,
    weights = weights.size,
    savedMeals = savedMeals.size,
    targets = targets.size,
    hasProfile = profile != null,
)

/**
 * Backup/restore engine for Google Drive backup.
 *
 * [restore] is the only path that can destroy a user's real logged history, so it always writes
 * a pre-restore snapshot to [snapshotDir] before touching the database, and the DB mutation runs
 * inside a single Room transaction so a mid-restore failure rolls back cleanly.
 */
class BackupManager(
    private val db: AppDatabase,
    private val profileDao: UserProfileDao,
    private val weightDao: WeightEntryDao,
    private val foodDao: FoodEntryDao,
    private val targetDao: TargetPeriodDao,
    private val savedMealDao: SavedMealDao,
    private val trendCacheDao: WeightTrendCacheDao,
    private val currentUser: CurrentUser,
    private val themeStore: ThemeStore,
    private val snapshotDir: File,
    private val appVersionCode: Int,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Envelope JSON for the current user's data, suitable for [restore] or a Drive upload. */
    suspend fun backup(): String {
        val uid = currentUser.userId()
        val data = BackupData(
            originUserId = uid,
            createdAt = clock.instant().toEpochMilli(),
            themePreference = themeStore.preference.value.name,
            profile = profileDao.get(uid),
            // getAll (not getActive): soft-deleted food rows must survive a backup/restore round-trip.
            weights = weightDao.getAll(uid),
            foods = foodDao.getAll(uid),
            targets = targetDao.getAll(uid),
            savedMeals = savedMealDao.getForUser(uid),
        )
        return BackupCodec.encode(data, appVersionCode)
    }

    /** Row counts for the current user's local data, for the confirm dialog. */
    suspend fun localCounts(): BackupCounts {
        val uid = currentUser.userId()
        return BackupCounts(
            foods = foodDao.getAll(uid).size,
            weights = weightDao.getAll(uid).size,
            savedMeals = savedMealDao.getForUser(uid).size,
            targets = targetDao.getAll(uid).size,
            hasProfile = profileDao.get(uid) != null,
        )
    }

    /** Decodes [json] and counts rows, with no DB access and no side effects. */
    fun countsOf(json: String): BackupCounts = BackupCodec.decode(json).counts()

    /** Snapshots on device, newest first. */
    fun listSnapshots(): List<File> =
        snapshotDir.listFiles { f -> f.isFile && f.name.startsWith(SNAPSHOT_PREFIX) }
            ?.sortedDescending()
            ?: emptyList()

    /**
     * Replaces all of the current user's rows with the contents of [json].
     *
     * Order matters and must not change:
     * 1. Decode (any [BackupFormatException] propagates before anything is read or written).
     * 2. Write a pre-restore snapshot; if that fails, abort — the DB is never touched.
     * 3. Delete + reinsert the user's rows in one transaction (atomic rollback on failure).
     * 4. Apply the theme (outside the transaction — it isn't a Room table).
     */
    suspend fun restore(json: String): RestoreResult {
        val data = BackupCodec.decode(json)
        val uid = currentUser.userId()

        val snapshotPath = writeSnapshot()

        db.withTransaction {
            // Wipe this user's rows only — other users' rows are untouched by these per-user deletes.
            profileDao.delete(uid)
            weightDao.deleteAll(uid)
            foodDao.deleteAll(uid)
            targetDao.deleteAll(uid)
            savedMealDao.deleteAll(uid)
            // Derived cache — TdeeRepository recomputes it on next read, so it is never restored.
            trendCacheDao.deleteAll(uid)

            data.profile?.let { profileDao.upsert(it.copy(userId = uid)) }
            // id = 0 lets SQLite assign fresh ids: reusing backup ids under REPLACE could clobber a
            // different local user's row (deletes above are per-user, ids are global). mealId is a
            // TEXT grouping key, not a row id, so it is preserved verbatim and meal groups survive.
            weightDao.insertAll(data.weights.map { it.copy(id = 0, userId = uid) })
            foodDao.insertAll(data.foods.map { it.copy(id = 0, userId = uid) })
            targetDao.insertAll(data.targets.map { it.copy(id = 0, userId = uid) })
            savedMealDao.insertAll(data.savedMeals.map { it.copy(id = 0, userId = uid) })
        }

        data.themePreference?.let { pref ->
            runCatching { ThemePreference.valueOf(pref) }.getOrNull()?.let { themeStore.set(it) }
        }

        return RestoreResult(counts = data.counts(), snapshotPath = snapshotPath)
    }

    /** Writes the current user's backup to a timestamped snapshot file and prunes old ones. */
    private suspend fun writeSnapshot(): String {
        if (!snapshotDir.isDirectory && !snapshotDir.mkdirs()) {
            throw java.io.IOException("Could not create snapshot directory: $snapshotDir")
        }
        val file = File(snapshotDir, "$SNAPSHOT_PREFIX${clock.millis()}.json")
        val json = backup()
        file.outputStream().use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }

        listSnapshots().drop(MAX_SNAPSHOTS).forEach { it.delete() }

        return file.absolutePath
    }

    companion object {
        private const val SNAPSHOT_PREFIX = "pre-restore-"
        private const val MAX_SNAPSHOTS = 3
    }
}
