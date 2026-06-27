package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tests for the logging extension methods added to [TdeeRepository]:
 *   - [TdeeRepository.addFood]
 *   - [TdeeRepository.addWeight]
 *   - [TdeeRepository.todayFoodEntries]
 *   - [TdeeRepository.todayConsumedMacros]
 *   - [TdeeRepository.softDeleteFood]
 *
 * Uses an in-memory Room database, a fake [CurrentUser], and a fixed [Clock].
 * Fixed "now" = 2026-06-21T12:00:00Z → log-day 2026-06-21 (dayStartHour = 0, UTC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryLoggingTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "logging-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    @Before
    fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        // Insert a profile so day-start queries can resolve dayStartHour.
        db.userProfileDao().upsert(makeProfile(userId))

        repo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            savedMealDao = db.savedMealDao(),
            currentUser = fakeCurrentUser,
            zone = zone,
            clock = fixedClock,
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // addFood
    // -----------------------------------------------------------------------

    @Test
    fun `addFood persists entry under current user with MANUAL source`() = runTest {
        repo.addFood(name = "Oats", kcal = 300.0, proteinG = 10.0, fatG = 5.0, carbG = 55.0)

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals(userId, e.userId)
        assertEquals(FoodSourceDb.MANUAL, e.sourceDb)
        assertEquals("Oats", e.name)
        assertEquals("Oats", e.rawText)
        assertEquals(300.0, e.kcal, 0.001)
        assertEquals(10.0, e.proteinG, 0.001)
        assertEquals(5.0, e.fatG, 0.001)
        assertEquals(55.0, e.carbG, 0.001)
        assertEquals(fixedNow, e.timestamp)
        assertEquals(fixedNow, e.createdAt)
        assertEquals(fixedNow, e.updatedAt)
    }

    @Test
    fun `addFood stores optional grams when provided`() = runTest {
        repo.addFood(name = "Rice", kcal = 200.0, proteinG = 4.0, fatG = 1.0, carbG = 44.0, grams = 150.0)

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(150.0, entries[0].grams, 0.001)
    }

    @Test
    fun `addFood defaults grams to 0 when not provided`() = runTest {
        repo.addFood(name = "Apple", kcal = 95.0, proteinG = 0.5, fatG = 0.3, carbG = 25.0)

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(0.0, entries[0].grams, 0.001)
    }

    @Test
    fun `addFood entry is not visible under a different userId`() = runTest {
        repo.addFood(name = "Egg", kcal = 70.0, proteinG = 6.0, fatG = 5.0, carbG = 0.5)

        val otherUserEntries = db.foodEntryDao().getActive("other-user")
        assertTrue("Other user should see no entries", otherUserEntries.isEmpty())
    }

    // -----------------------------------------------------------------------
    // addWeight
    // -----------------------------------------------------------------------

    @Test
    fun `addWeight persists entry under current user with MANUAL source`() = runTest {
        repo.addWeight(175.0)

        val entries = db.weightEntryDao().getAll(userId)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals(userId, e.userId)
        assertEquals(WeightSource.MANUAL, e.source)
        assertEquals(fixedNow, e.timestamp)
        assertEquals(fixedNow, e.createdAt)
    }

    @Test
    fun `addWeight converts lb to kg correctly`() = runTest {
        repo.addWeight(154.0)

        val entries = db.weightEntryDao().getAll(userId)
        assertEquals(1, entries.size)
        // 154 lb × 0.45359237 = 69.853 kg
        assertEquals(154.0 * 0.45359237, entries[0].weightKg, 0.0001)
    }

    @Test
    fun `addWeight 220 lb converts to approximately 99_79 kg`() = runTest {
        repo.addWeight(220.0)

        val entries = db.weightEntryDao().getAll(userId)
        assertEquals(220.0 * 0.45359237, entries[0].weightKg, 0.0001)
    }

    @Test
    fun `addWeight entry is not visible under a different userId`() = runTest {
        repo.addWeight(180.0)

        val otherEntries = db.weightEntryDao().getAll("other-user")
        assertTrue("Other user should see no weight entries", otherEntries.isEmpty())
    }

    // -----------------------------------------------------------------------
    // todayFoodEntries
    // -----------------------------------------------------------------------

    @Test
    fun `todayFoodEntries returns only today non-deleted entries for current user`() = runTest {
        // Today entry
        db.foodEntryDao().insert(makeFoodEntry(userId, fixedNow, kcal = 400.0))
        // Yesterday entry — should be excluded
        db.foodEntryDao().insert(makeFoodEntry(userId, Instant.parse("2026-06-20T12:00:00Z"), kcal = 500.0))
        // Today entry for a different user — should be excluded
        db.foodEntryDao().insert(makeFoodEntry("other-user", fixedNow, kcal = 600.0))
        // Today entry that is soft-deleted — should be excluded
        val deletedId = db.foodEntryDao().insert(makeFoodEntry(userId, fixedNow, kcal = 700.0))
        db.foodEntryDao().softDelete(deletedId, fixedNow)

        val entries = repo.todayFoodEntries()

        assertEquals(1, entries.size)
        assertEquals(400.0, entries[0].kcal, 0.001)
        assertEquals(userId, entries[0].userId)
    }

    @Test
    fun `todayFoodEntries returns empty list when no entries today`() = runTest {
        val entries = repo.todayFoodEntries()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `todayFoodEntries excludes entries from the next log-day`() = runTest {
        // Tomorrow's entry (2026-06-22 log-day)
        db.foodEntryDao().insert(makeFoodEntry(userId, Instant.parse("2026-06-22T06:00:00Z"), kcal = 300.0))

        val entries = repo.todayFoodEntries()
        assertTrue(entries.isEmpty())
    }

    // -----------------------------------------------------------------------
    // todayConsumedMacros
    // -----------------------------------------------------------------------

    @Test
    fun `todayConsumedMacros returns all zeros when no entries exist`() = runTest {
        val macros = repo.todayConsumedMacros()
        assertEquals(0.0, macros.kcal, 0.001)
        assertEquals(0.0, macros.proteinG, 0.001)
        assertEquals(0.0, macros.fatG, 0.001)
        assertEquals(0.0, macros.carbG, 0.001)
    }

    @Test
    fun `todayConsumedMacros sums macros across all today entries`() = runTest {
        repo.addFood("Chicken", kcal = 250.0, proteinG = 40.0, fatG = 8.0, carbG = 0.0)
        repo.addFood("Rice", kcal = 200.0, proteinG = 4.0, fatG = 1.0, carbG = 44.0)

        val macros = repo.todayConsumedMacros()
        assertEquals(450.0, macros.kcal, 0.001)
        assertEquals(44.0, macros.proteinG, 0.001)
        assertEquals(9.0, macros.fatG, 0.001)
        assertEquals(44.0, macros.carbG, 0.001)
    }

    // -----------------------------------------------------------------------
    // softDeleteFood
    // -----------------------------------------------------------------------

    @Test
    fun `softDeleteFood removes entry from todayFoodEntries`() = runTest {
        val id = db.foodEntryDao().insert(makeFoodEntry(userId, fixedNow, kcal = 300.0))

        // Confirm it's there first
        assertEquals(1, repo.todayFoodEntries().size)

        repo.softDeleteFood(id)

        assertTrue("Entry should be excluded after soft-delete", repo.todayFoodEntries().isEmpty())
    }

    @Test
    fun `softDeleteFood sets deletedAt to clock instant`() = runTest {
        val id = db.foodEntryDao().insert(makeFoodEntry(userId, fixedNow, kcal = 300.0))
        repo.softDeleteFood(id)

        val all = db.foodEntryDao().getAll(userId)
        assertEquals(1, all.size)
        assertEquals(fixedNow, all[0].deletedAt)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeProfile(uid: String) = UserProfileEntity(
        userId = uid,
        sex = Sex.MALE,
        birthYear = 1990,
        heightCm = 175.0,
        activityLevel = ActivityLevel.MODERATE,
        goalRateKgPerWeek = -0.25,
        goalWeightKg = 75.0,
        dayStartHour = 0,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private fun makeFoodEntry(uid: String, timestamp: Instant, kcal: Double) = FoodEntryEntity(
        userId = uid,
        timestamp = timestamp,
        rawText = "test",
        name = "Test Food",
        quantity = 1.0,
        unit = "serving",
        grams = 100.0,
        kcal = kcal,
        proteinG = 20.0,
        fatG = 10.0,
        carbG = 50.0,
        sourceDb = FoodSourceDb.MANUAL,
        createdAt = timestamp,
        updatedAt = timestamp,
    )
}
