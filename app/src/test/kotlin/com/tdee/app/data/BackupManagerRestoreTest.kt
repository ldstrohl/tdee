package com.tdee.app.data

import androidx.room.Room
import com.tdee.app.ui.theme.ThemePreference
import com.tdee.app.ui.theme.ThemeStore
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tests for [BackupManager.restore] — the only path that can destroy a user's real logged
 * history. Covers cross-device round-trip, replace-all semantics, other-user isolation,
 * rollback on mid-restore failure, the pre-restore snapshot (including its own restore path and
 * a write-failure abort), trend-cache invalidation, and rejecting a malformed backup untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerRestoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var profileDao: UserProfileDao
    private lateinit var weightDao: WeightEntryDao
    private lateinit var foodDao: FoodEntryDao
    private lateinit var targetDao: TargetPeriodDao
    private lateinit var savedMealDao: SavedMealDao
    private lateinit var trendCacheDao: WeightTrendCacheDao
    private lateinit var themeStore: ThemeStore
    private lateinit var snapshotDir: File

    private val fixedNow = Instant.parse("2026-06-22T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    /** Mutable so a single manager can act as "device A" then "device B" in a test. */
    private class MutableCurrentUser(var id: String) : CurrentUser {
        override fun userId(): String = id
    }

    /** Ticks 1s per call so successive snapshot filenames (epoch millis) never collide. */
    private class TickingClock(start: Instant) : Clock() {
        private var current = start
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current.also { current = current.plusSeconds(1) }
    }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        profileDao = db.userProfileDao()
        weightDao = db.weightEntryDao()
        foodDao = db.foodEntryDao()
        targetDao = db.targetPeriodDao()
        savedMealDao = db.savedMealDao()
        trendCacheDao = db.weightTrendCacheDao()
        themeStore = ThemeStore(RuntimeEnvironment.getApplication())
        snapshotDir = tmp.newFolder("snap")
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun manager(
        currentUser: CurrentUser,
        clock: Clock = fixedClock,
        dir: File = snapshotDir,
        foodDaoOverride: FoodEntryDao = foodDao,
    ) = BackupManager(
        db = db,
        profileDao = profileDao,
        weightDao = weightDao,
        foodDao = foodDaoOverride,
        targetDao = targetDao,
        savedMealDao = savedMealDao,
        trendCacheDao = trendCacheDao,
        currentUser = currentUser,
        themeStore = themeStore,
        snapshotDir = dir,
        appVersionCode = 1,
        clock = clock,
    )

    // -----------------------------------------------------------------------
    // 1. Cross-device round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `cross-device round-trip preserves data and reassigns ids to the new user`() = runTest {
        val user = MutableCurrentUser("A")

        profileDao.upsert(makeProfile("A"))
        val w1 = makeWeight("A", ts = "2026-01-01T08:00:00Z", healthConnectUid = "hc-1")
        val w2 = makeWeight("A", ts = "2026-01-02T08:00:00Z", bodyFatPct = 18.5)
        val w3 = makeWeight("A", ts = "2026-01-03T08:00:00Z")
        weightDao.insertAll(listOf(w1, w2, w3))

        val mealId = "meal-group-1"
        val f1 = makeFood("A", ts = "2026-01-01T12:00:00Z", name = "Deleted A", deleted = true)
        val f2 = makeFood("A", ts = "2026-01-01T13:00:00Z", name = "Deleted B", deleted = true)
        val f3 = makeFood("A", ts = "2026-01-01T18:00:00Z", name = "Chicken", mealId = mealId, mealName = "Dinner")
        val f4 = makeFood("A", ts = "2026-01-01T18:00:01Z", name = "Rice", mealId = mealId, mealName = "Dinner")
        val f5 = makeFood("A", ts = "2026-01-02T09:00:00Z", name = "Standalone")
        foodDao.insertAll(listOf(f1, f2, f3, f4, f5))

        targetDao.insertAll(listOf(makeTarget("A", LocalDate.of(2026, 1, 1)), makeTarget("A", LocalDate.of(2026, 1, 8))))

        savedMealDao.insertAll(
            listOf(
                makeSavedMeal("A", "Simple", listOf(SavedMealItem("Egg", 70.0, 6.0, 5.0, 1.0, 50.0))),
                makeSavedMeal(
                    "A",
                    "Combo",
                    listOf(
                        SavedMealItem("Toast", 120.0, 4.0, 2.0, 20.0, 40.0),
                        SavedMealItem("Butter", 100.0, 0.0, 11.0, 0.0, 14.0),
                    ),
                ),
            ),
        )

        themeStore.set(ThemePreference.DARK)
        val bmA = manager(user)
        val backupJson = bmA.backup()

        // Wipe everything (simulates a fresh device) and switch to user B.
        profileDao.deleteAll()
        weightDao.deleteAll()
        foodDao.deleteAll()
        targetDao.deleteAll()
        savedMealDao.deleteAll("A")
        themeStore.set(ThemePreference.LIGHT)
        user.id = "B"

        val bmB = manager(user)
        val result = bmB.restore(backupJson)

        assertEquals(3, result.counts.weights)
        assertEquals(5, result.counts.foods)
        assertEquals(2, result.counts.targets)
        assertEquals(2, result.counts.savedMeals)
        assertTrue(result.counts.hasProfile)

        val weights = weightDao.getAll("B")
        assertEquals(3, weights.size)
        assertTrue(weights.all { it.userId == "B" })
        assertTrue(weights.any { it.healthConnectUid == "hc-1" })
        assertTrue(weights.any { it.bodyFatPct == 18.5 })
        // ids reassigned, not the origin ids
        assertTrue(weights.none { it.id == w1.id || it.id == w2.id || it.id == w3.id })

        val foods = foodDao.getAll("B")
        assertEquals(5, foods.size)
        assertTrue(foods.all { it.userId == "B" })
        val deleted = foods.filter { it.deletedAt != null }
        assertEquals(2, deleted.size)
        assertTrue(deleted.all { it.name.startsWith("Deleted") })

        val dinnerGroup = foods.filter { it.name == "Chicken" || it.name == "Rice" }
        assertEquals(2, dinnerGroup.size)
        val groupMealIds = dinnerGroup.map { it.mealId }.toSet()
        assertEquals(setOf(mealId), groupMealIds)
        assertTrue(dinnerGroup.all { it.mealName == "Dinner" })

        val savedMeals = savedMealDao.getForUser("B")
        assertEquals(2, savedMeals.size)
        assertTrue(savedMeals.all { it.userId == "B" })
        val combo = savedMeals.first { it.name == "Combo" }
        assertEquals(
            listOf(
                SavedMealItem("Toast", 120.0, 4.0, 2.0, 20.0, 40.0),
                SavedMealItem("Butter", 100.0, 0.0, 11.0, 0.0, 14.0),
            ),
            combo.items,
        )

        assertEquals(ThemePreference.DARK, themeStore.preference.value)
    }

    // -----------------------------------------------------------------------
    // 2. Replace-all
    // -----------------------------------------------------------------------

    @Test
    fun `restore replaces all of the target user's rows, none of the originals survive`() = runTest {
        val user = MutableCurrentUser("B")
        // ~20 unrelated pre-existing rows for B.
        profileDao.upsert(makeProfile("B"))
        weightDao.insertAll((1..8).map { makeWeight("B", ts = "2025-0${(it % 9) + 1}-01T00:00:00Z") })
        foodDao.insertAll((1..6).map { makeFood("B", ts = "2025-01-0${(it % 9) + 1}T00:00:00Z", name = "Old $it") })
        targetDao.insertAll((1..2).map { makeTarget("B", LocalDate.of(2025, 1, it)) })
        savedMealDao.insertAll((1..2).map { makeSavedMeal("B", "OldMeal$it", listOf(SavedMealItem("X", 1.0, 1.0, 1.0, 1.0, 1.0))) })

        val other = MutableCurrentUser("Z")
        weightDao.insertAll(listOf(makeWeight("Z")))
        val onlyWeight = makeWeight("B", ts = "2026-05-01T00:00:00Z")
        foodDao.insertAll(listOf(makeFood("B", ts = "2026-05-01T00:00:00Z", name = "New")))
        weightDao.insertAll(listOf(onlyWeight))

        // Build a 3-row backup independently (not derived from B's current state).
        val smallBackup = BackupCodec.encode(
            BackupData(
                originUserId = "elsewhere",
                createdAt = fixedNow.toEpochMilli(),
                themePreference = null,
                profile = null,
                weights = listOf(makeWeight("elsewhere", ts = "2026-06-01T00:00:00Z")),
                foods = listOf(makeFood("elsewhere", ts = "2026-06-01T00:00:00Z", name = "Fresh")),
                targets = emptyList(),
                savedMeals = listOf(makeSavedMeal("elsewhere", "OnlyMeal", listOf(SavedMealItem("Y", 2.0, 2.0, 2.0, 2.0, 2.0)))),
            ),
            appVersionCode = 1,
        )

        val bm = manager(user)
        val result = bm.restore(smallBackup)

        assertEquals(1, result.counts.weights)
        assertEquals(1, result.counts.foods)
        assertEquals(1, result.counts.savedMeals)
        assertEquals(0, result.counts.targets)
        assertFalse(result.counts.hasProfile)

        assertEquals(1, weightDao.getAll("B").size)
        assertEquals("Fresh", foodDao.getAll("B").single().name)
        assertEquals(1, savedMealDao.getForUser("B").size)
        assertEquals(0, targetDao.getAll("B").size)
        assertNull(profileDao.get("B"))
        // Z untouched by a restore for B.
        assertEquals(1, weightDao.getAll("Z").size)
    }

    // -----------------------------------------------------------------------
    // 3. Other users untouched
    // -----------------------------------------------------------------------

    @Test
    fun `restoring for one user leaves another user's rows byte-identical`() = runTest {
        val userC = MutableCurrentUser("C")
        profileDao.upsert(makeProfile("C"))
        weightDao.insertAll(listOf(makeWeight("C")))
        foodDao.insertAll(listOf(makeFood("C", name = "C's food")))
        targetDao.insertAll(listOf(makeTarget("C", LocalDate.of(2026, 1, 1))))
        savedMealDao.insertAll(listOf(makeSavedMeal("C", "C's meal", listOf(SavedMealItem("Z", 1.0, 1.0, 1.0, 1.0, 1.0)))))

        val bmC = manager(userC)
        val beforeJson = bmC.backup()

        val userB = MutableCurrentUser("B")
        val bmB = manager(userB)
        val bBackup = BackupCodec.encode(
            BackupData(
                originUserId = "B",
                createdAt = fixedNow.toEpochMilli(),
                themePreference = null,
                profile = null,
                weights = listOf(makeWeight("B")),
                foods = emptyList(),
                targets = emptyList(),
                savedMeals = emptyList(),
            ),
            appVersionCode = 1,
        )
        bmB.restore(bBackup)

        val afterJson = bmC.backup()
        assertEquals(beforeJson, afterJson)
    }

    // -----------------------------------------------------------------------
    // 4. Rollback on mid-restore failure
    // -----------------------------------------------------------------------

    private class ThrowingFoodEntryDao(private val delegate: FoodEntryDao) : FoodEntryDao by delegate {
        override suspend fun insertAll(entries: List<FoodEntryEntity>): List<Long> {
            throw RuntimeException("forced failure for rollback test")
        }
    }

    @Test
    fun `a failure partway through the transaction rolls back everything`() = runTest {
        val user = MutableCurrentUser("B")
        profileDao.upsert(makeProfile("B"))
        weightDao.insertAll(listOf(makeWeight("B")))
        foodDao.insertAll(listOf(makeFood("B", name = "Untouched")))

        val readOnlyManager = manager(user)
        val beforeJson = readOnlyManager.backup()

        val backupJson = BackupCodec.encode(
            BackupData(
                originUserId = "B",
                createdAt = fixedNow.toEpochMilli(),
                themePreference = ThemePreference.DARK.name,
                profile = null,
                weights = listOf(makeWeight("B", ts = "2026-07-01T00:00:00Z")),
                foods = listOf(makeFood("B", ts = "2026-07-01T00:00:00Z", name = "Should not persist")),
                targets = emptyList(),
                savedMeals = emptyList(),
            ),
            appVersionCode = 1,
        )

        val throwingManager = manager(user, foodDaoOverride = ThrowingFoodEntryDao(foodDao))

        var threw = false
        try {
            throwingManager.restore(backupJson)
        } catch (e: RuntimeException) {
            threw = true
            assertEquals("forced failure for rollback test", e.message)
        }
        assertTrue("expected the forced exception to propagate", threw)

        val afterJson = readOnlyManager.backup()
        assertEquals(beforeJson, afterJson)
        assertEquals(ThemePreference.SYSTEM, themeStore.preference.value) // untouched: default, never set
    }

    // -----------------------------------------------------------------------
    // 5. Snapshot written before the transaction + pruning
    // -----------------------------------------------------------------------

    @Test
    fun `snapshot decodes to pre-restore state and only the newest 3 survive`() = runTest {
        val user = MutableCurrentUser("B")
        val tickingClock = TickingClock(fixedNow)
        val bm = manager(user, clock = tickingClock)

        var lastSnapshotPath: String? = null
        var expectedPreRestoreFoodCount = 0

        repeat(5) { i ->
            // State right before this restore: i foods present (grows each round via the restore itself).
            expectedPreRestoreFoodCount = foodDao.getAll("B").size

            val newBackup = BackupCodec.encode(
                BackupData(
                    originUserId = "B",
                    createdAt = fixedNow.toEpochMilli(),
                    themePreference = null,
                    profile = null,
                    weights = emptyList(),
                    foods = (0..i).map { makeFood("B", ts = "2026-0${i + 1}-01T0$it:00:00Z", name = "Food$i-$it") },
                    targets = emptyList(),
                    savedMeals = emptyList(),
                ),
                appVersionCode = 1,
            )
            val result = bm.restore(newBackup)
            lastSnapshotPath = result.snapshotPath

            val snapshotData = BackupCodec.decode(File(result.snapshotPath).readText())
            assertEquals(expectedPreRestoreFoodCount, snapshotData.foods.size)
        }

        assertTrue(lastSnapshotPath != null)
        val remaining = bm.listSnapshots()
        assertEquals(3, remaining.size)
        // Newest first.
        assertTrue(remaining[0].name >= remaining[1].name)
        assertTrue(remaining[1].name >= remaining[2].name)
    }

    // -----------------------------------------------------------------------
    // 6. Snapshot-write failure aborts the restore
    // -----------------------------------------------------------------------

    @Test
    fun `snapshot write failure aborts the restore and leaves the DB unchanged`() = runTest {
        val user = MutableCurrentUser("B")
        profileDao.upsert(makeProfile("B"))
        weightDao.insertAll(listOf(makeWeight("B")))

        val readOnlyManager = manager(user)
        val beforeJson = readOnlyManager.backup()

        // File-in-place-of-directory: snapshotDir path already exists as a plain file, so
        // isDirectory is false and mkdirs() cannot succeed.
        val blockedDir = tmp.newFile("blocked-snapshot-dir")
        val badManager = manager(user, dir = blockedDir)

        val backupJson = BackupCodec.encode(
            BackupData(
                originUserId = "B",
                createdAt = fixedNow.toEpochMilli(),
                themePreference = null,
                profile = null,
                weights = listOf(makeWeight("B", ts = "2026-08-01T00:00:00Z")),
                foods = emptyList(),
                targets = emptyList(),
                savedMeals = emptyList(),
            ),
            appVersionCode = 1,
        )

        var threw = false
        try {
            badManager.restore(backupJson)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("expected the snapshot write to fail and abort", threw)

        val afterJson = readOnlyManager.backup()
        assertEquals(beforeJson, afterJson)
        assertEquals(1, weightDao.getAll("B").size)
    }

    // -----------------------------------------------------------------------
    // 7. Trend cache invalidated
    // -----------------------------------------------------------------------

    @Test
    fun `restore clears the derived weight trend cache and never repopulates it`() = runTest {
        val user = MutableCurrentUser("B")
        trendCacheDao.upsertAll(
            listOf(
                WeightTrendCacheEntity("B", LocalDate.of(2026, 1, 1), 80.0, 2500.0, TdeeMethodDb.EMPIRICAL, 100.0, false),
                WeightTrendCacheEntity("B", LocalDate.of(2026, 1, 2), 80.1, 2500.0, TdeeMethodDb.EMPIRICAL, 100.0, false),
            ),
        )
        assertEquals(2, trendCacheDao.getAll("B").size)

        val bm = manager(user)
        val emptyBackup = BackupCodec.encode(
            BackupData("B", fixedNow.toEpochMilli(), null, null, emptyList(), emptyList(), emptyList(), emptyList()),
            appVersionCode = 1,
        )
        bm.restore(emptyBackup)

        assertEquals(0, trendCacheDao.getAll("B").size)
    }

    // -----------------------------------------------------------------------
    // 8. Empty backup wipes cleanly
    // -----------------------------------------------------------------------

    @Test
    fun `empty backup wipes the user cleanly without crashing`() = runTest {
        val user = MutableCurrentUser("B")
        profileDao.upsert(makeProfile("B"))
        weightDao.insertAll(listOf(makeWeight("B")))
        foodDao.insertAll(listOf(makeFood("B")))

        val bm = manager(user)
        val emptyBackup = BackupCodec.encode(
            BackupData("B", fixedNow.toEpochMilli(), null, null, emptyList(), emptyList(), emptyList(), emptyList()),
            appVersionCode = 1,
        )
        val result = bm.restore(emptyBackup)

        assertEquals(0, result.counts.weights)
        assertEquals(0, result.counts.foods)
        assertFalse(result.counts.hasProfile)
        assertNull(profileDao.get("B"))
        assertTrue(weightDao.getAll("B").isEmpty())
        assertTrue(foodDao.getAll("B").isEmpty())
    }

    // -----------------------------------------------------------------------
    // 9. The snapshot itself restores (recovery path)
    // -----------------------------------------------------------------------

    @Test
    fun `a device snapshot can itself be fed back into restore to recover`() = runTest {
        val user = MutableCurrentUser("B")
        profileDao.upsert(makeProfile("B"))
        weightDao.insertAll(listOf(makeWeight("B", ts = "2026-01-01T00:00:00Z")))
        foodDao.insertAll(listOf(makeFood("B", ts = "2026-01-01T00:00:00Z", name = "Original")))

        val bm = manager(user, clock = TickingClock(fixedNow))
        val originalStateJson = bm.backup() // S0, taken independently for comparison

        val replacementBackup = BackupCodec.encode(
            BackupData(
                originUserId = "B",
                createdAt = fixedNow.toEpochMilli(),
                themePreference = null,
                profile = null,
                weights = emptyList(),
                foods = listOf(makeFood("B", ts = "2026-09-01T00:00:00Z", name = "Replacement")),
                targets = emptyList(),
                savedMeals = emptyList(),
            ),
            appVersionCode = 1,
        )
        val firstRestore = bm.restore(replacementBackup) // snapshot on disk now == S0
        assertEquals("Replacement", foodDao.getAll("B").single().name)

        val snapshotJson = File(firstRestore.snapshotPath).readText()
        bm.restore(snapshotJson) // recovery: restore the snapshot

        val recoveredFoods = foodDao.getAll("B")
        assertEquals(1, recoveredFoods.size)
        assertEquals("Original", recoveredFoods.single().name)
        val recoveredWeights = weightDao.getAll("B")
        assertEquals(1, recoveredWeights.size)
        assertEquals(80.0, recoveredWeights.single().weightKg, 0.0001)

        // Structural equivalence with S0 (ids are necessarily reassigned each restore).
        val originalNormalized = BackupCodec.decode(originalStateJson).let { it.foods.map { f -> f.name } }
        val recoveredNormalized = recoveredFoods.map { it.name }
        assertEquals(originalNormalized, recoveredNormalized)
    }

    // -----------------------------------------------------------------------
    // 10. Malformed backup leaves the DB untouched
    // -----------------------------------------------------------------------

    @Test
    fun `a BackupFormatException from a bad envelope leaves the DB untouched and writes no snapshot`() = runTest {
        val user = MutableCurrentUser("B")
        profileDao.upsert(makeProfile("B"))
        weightDao.insertAll(listOf(makeWeight("B")))

        val bm = manager(user)
        val beforeJson = bm.backup()
        val snapshotsBefore = bm.listSnapshots().size

        var threw = false
        try {
            bm.restore("{ this is not a valid backup at all")
        } catch (e: BackupFormatException) {
            threw = true
        }
        assertTrue("expected BackupFormatException", threw)

        assertEquals(beforeJson, bm.backup())
        assertEquals(snapshotsBefore, bm.listSnapshots().size)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeProfile(userId: String): UserProfileEntity = UserProfileEntity(
        userId = userId,
        sex = Sex.FEMALE,
        birthYear = 1992,
        heightCm = 168.0,
        activityLevel = ActivityLevel.MODERATE,
        goalRateKgPerWeek = -0.25,
        goalWeightKg = 65.0,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private fun makeWeight(
        userId: String,
        ts: String = "2026-01-01T08:00:00Z",
        healthConnectUid: String? = null,
        bodyFatPct: Double? = null,
    ): WeightEntryEntity = WeightEntryEntity(
        userId = userId,
        timestamp = Instant.parse(ts),
        weightKg = 80.0,
        bodyFatPct = bodyFatPct,
        source = if (healthConnectUid != null) WeightSource.HEALTH_CONNECT else WeightSource.MANUAL,
        healthConnectUid = healthConnectUid,
        createdAt = fixedNow,
    )

    private fun makeFood(
        userId: String,
        ts: String = "2026-01-01T12:00:00Z",
        name: String = "Test Food",
        mealId: String? = null,
        mealName: String? = null,
        deleted: Boolean = false,
    ): FoodEntryEntity = FoodEntryEntity(
        userId = userId,
        timestamp = Instant.parse(ts),
        rawText = name,
        name = name,
        quantity = 1.0,
        unit = "serving",
        grams = 100.0,
        kcal = 200.0,
        proteinG = 10.0,
        fatG = 5.0,
        carbG = 20.0,
        sourceDb = FoodSourceDb.MANUAL,
        mealId = mealId,
        mealName = mealName,
        createdAt = fixedNow,
        updatedAt = fixedNow,
        deletedAt = if (deleted) fixedNow else null,
    )

    private fun makeTarget(userId: String, startDate: LocalDate): TargetPeriodEntity = TargetPeriodEntity(
        userId = userId,
        startDate = startDate,
        endDate = startDate.plusDays(7),
        tdeeAtCheckin = 2500.0,
        calorieTarget = 2200.0,
        proteinTargetG = 150.0,
        fatTargetG = 70.0,
        carbTargetG = 220.0,
        acceptedAt = fixedNow,
    )

    private fun makeSavedMeal(userId: String, name: String, items: List<SavedMealItem>): SavedMealEntity =
        SavedMealEntity(userId = userId, name = name, items = items, createdAt = fixedNow)
}
