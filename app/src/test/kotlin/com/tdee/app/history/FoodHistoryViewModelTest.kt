package com.tdee.app.history

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.NewFoodItem
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests for [FoodHistoryViewModel.logMealToDate] / [FoodHistoryViewModel.logEntryToDate], which
 * re-log an existing meal group or standalone entry to an arbitrary (past or present) log-day via
 * [TdeeRepository.repeatMeal] / [TdeeRepository.repeatEntry].
 *
 * Uses an in-memory Room database, a fake [CurrentUser], and a fixed [Clock].
 * Fixed "now" = 2026-06-21T12:00:00Z -> log-day 2026-06-21 (dayStartHour = 0, UTC).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodHistoryViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val pastDate = LocalDate.of(2026, 6, 15)

    private val userId = "food-history-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.userProfileDao().upsert(
            UserProfileEntity(
                userId = userId,
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
        )

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
        Dispatchers.resetMain()
    }

    @Test
    fun `logMealToDate inserts a scaled copy of the group on the target log-day`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            ),
            mealName = "Breakfast",
        )
        val vm = FoodHistoryViewModel(repo)
        vm.setDate(pastDate)

        vm.logMealToDate(mealId, pastDate, 2.0)

        val targetEntries = vm.entries.filter { it.size == 2 }.first()
        assertTrue(targetEntries.all { it.mealName == "Breakfast" })
        assertTrue(targetEntries.none { it.mealId == mealId })
        val kcals = targetEntries.map { it.kcal }.sorted()
        assertEquals(listOf(190.0, 210.0), kcals)
    }

    @Test
    fun `logEntryToDate inserts a scaled standalone copy on the target log-day`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        val vm = FoodHistoryViewModel(repo)
        vm.setDate(pastDate)

        vm.logEntryToDate(original.id, pastDate, 0.5)

        val targetEntries = vm.entries.filter { it.size == 1 }.first()
        assertEquals("Oats", targetEntries[0].name)
        assertEquals(150.0, targetEntries[0].kcal, 0.001)
        assertEquals(null, targetEntries[0].mealId)
    }
}
