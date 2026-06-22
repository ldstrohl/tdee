package com.tdee.app.addfood

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.data.FoodSourceDb
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.data.WeightEntryEntity
import com.tdee.app.data.WeightSource
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Tests for the dashboard's reactive today-food Flow:
 *   [TdeeRepository.observeTodayFoodEntries]
 *
 * Verifies that:
 *   - the Flow includes a food entry after [TdeeRepository.addFood]
 *   - the Flow excludes a food entry after [TdeeRepository.softDeleteFood]
 *
 * Uses Robolectric + in-memory Room with a fixed clock (2026-06-21T12:00:00Z, UTC),
 * matching the pattern established in TdeeRepositoryTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodayFoodFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "flow-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        val now = fixedNow
        db.userProfileDao().upsert(
            UserProfileEntity(
                userId = userId,
                sex = Sex.MALE,
                birthYear = 1990,
                heightCm = 175.0,
                activityLevel = ActivityLevel.MODERATE,
                goalRateKgPerWeek = 0.0,
                goalWeightKg = null,
                proteinGPerKg = 2.0,
                fatPctOfCalories = 0.25,
                dayStartHour = 0,
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = now,
                updatedAt = now,
            )
        )
        db.weightEntryDao().insert(
            WeightEntryEntity(
                userId = userId,
                timestamp = now,
                weightKg = 80.0,
                source = WeightSource.MANUAL,
                createdAt = now,
            )
        )

        repo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            currentUser = fakeCurrentUser,
            zone = zone,
            clock = fixedClock,
        )
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // 1. Flow starts empty
    // -----------------------------------------------------------------------

    @Test
    fun `observeTodayFoodEntries emits empty list when no food logged today`() = runTest {
        val list = repo.observeTodayFoodEntries().first()
        assertTrue(list.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 2. Flow includes entry after addFood
    // -----------------------------------------------------------------------

    @Test
    fun `observeTodayFoodEntries includes entry after addFood`() = runTest {
        repo.addFood(name = "Chicken breast", kcal = 165.0, proteinG = 31.0, fatG = 3.6, carbG = 0.0)

        val list = repo.observeTodayFoodEntries().first()

        assertEquals(1, list.size)
        assertEquals("Chicken breast", list[0].name)
        assertEquals(165.0, list[0].kcal, 0.001)
    }

    @Test
    fun `observeTodayFoodEntries sums multiple entries`() = runTest {
        repo.addFood(name = "Breakfast", kcal = 400.0, proteinG = 20.0, fatG = 15.0, carbG = 45.0)
        repo.addFood(name = "Lunch", kcal = 600.0, proteinG = 35.0, fatG = 20.0, carbG = 60.0)

        val list = repo.observeTodayFoodEntries().first()

        assertEquals(2, list.size)
        val totalKcal = list.sumOf { it.kcal }
        assertEquals(1000.0, totalKcal, 0.001)
    }

    // -----------------------------------------------------------------------
    // 3. Flow excludes entry after softDeleteFood
    // -----------------------------------------------------------------------

    @Test
    fun `observeTodayFoodEntries excludes soft-deleted entry`() = runTest {
        repo.addFood(name = "Yogurt", kcal = 100.0, proteinG = 10.0, fatG = 2.0, carbG = 12.0)

        val listBefore = repo.observeTodayFoodEntries().first()
        assertEquals(1, listBefore.size)

        val id = listBefore[0].id
        repo.softDeleteFood(id)

        val listAfter = repo.observeTodayFoodEntries().first()
        assertTrue("Soft-deleted entry should not appear in today's food flow", listAfter.isEmpty())
    }

    @Test
    fun `observeTodayFoodEntries only removes deleted entry leaving others intact`() = runTest {
        repo.addFood(name = "Oatmeal", kcal = 300.0, proteinG = 10.0, fatG = 5.0, carbG = 55.0)
        repo.addFood(name = "Coffee", kcal = 5.0, proteinG = 0.0, fatG = 0.0, carbG = 1.0)

        val listBefore = repo.observeTodayFoodEntries().first()
        assertEquals(2, listBefore.size)

        val oatmealId = listBefore.first { it.name == "Oatmeal" }.id
        repo.softDeleteFood(oatmealId)

        val listAfter = repo.observeTodayFoodEntries().first()
        assertEquals(1, listAfter.size)
        assertEquals("Coffee", listAfter[0].name)
    }

    // -----------------------------------------------------------------------
    // 4. Past-day entries are NOT included in today's window
    // -----------------------------------------------------------------------

    @Test
    fun `observeTodayFoodEntries does not include entries from previous days`() = runTest {
        // Insert a food entry on 2026-06-20 (yesterday relative to fixedNow).
        val yesterday = Instant.parse("2026-06-20T12:00:00Z")
        db.foodEntryDao().insert(
            FoodEntryEntity(
                userId = userId,
                timestamp = yesterday,
                rawText = "old food",
                name = "Yesterday's meal",
                quantity = 1.0,
                unit = "serving",
                grams = 200.0,
                kcal = 500.0,
                proteinG = 20.0,
                fatG = 10.0,
                carbG = 60.0,
                sourceDb = FoodSourceDb.MANUAL,
                createdAt = yesterday,
                updatedAt = yesterday,
            )
        )

        val list = repo.observeTodayFoodEntries().first()
        assertTrue("Yesterday's entries must not appear in today's food flow", list.isEmpty())
    }
}
