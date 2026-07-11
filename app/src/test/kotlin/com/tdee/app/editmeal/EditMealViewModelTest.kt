package com.tdee.app.editmeal

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
import org.junit.Assert.assertNotEquals
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
 * Tests for [EditMealViewModel]: in-place [EditMealViewModel.scaleMeal]/[EditMealViewModel.scaleItem]
 * and re-logging via [EditMealViewModel.logToDate] (delegates to [TdeeRepository.repeatMeal]).
 *
 * Uses an in-memory Room database, a fake [CurrentUser], and a fixed [Clock].
 * Fixed "now" = 2026-06-21T12:00:00Z -> log-day 2026-06-21 (dayStartHour = 0, UTC).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditMealViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val pastDate = LocalDate.of(2026, 6, 15)

    private val userId = "edit-meal-test-user"
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

    private suspend fun seedMeal(): String = repo.addFoodGroup(
        listOf(
            NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null),
            NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
        ),
        mealName = "Breakfast",
    )

    @Test
    fun `scaleMeal doubles every entry in place`() = runTest {
        val mealId = seedMeal()
        val originalIds = repo.mealEntries(mealId).map { it.id }.sorted()
        val vm = EditMealViewModel(repo, mealId)

        vm.scaleMeal(2.0)

        val updated = vm.entries.filter { it.size == 2 && it.all { e -> e.kcal > 100.0 } }.first()
        assertEquals(originalIds, updated.map { it.id }.sorted())
        val kcals = updated.map { it.kcal }.sorted()
        assertEquals(listOf(190.0, 210.0), kcals)
        val proteins = updated.map { it.proteinG }.sorted()
        assertEquals(listOf(1.0, 2.6), proteins)
        val fats = updated.map { it.fatG }.sorted()
        assertEquals(listOf(0.6, 0.8), fats)
        val carbs = updated.map { it.carbG }.sorted()
        assertEquals(listOf(50.0, 54.0), carbs)
        // grams was never set (NewFoodItem.grams = null), so the 0.0 "unknown" sentinel is preserved.
        val grams = updated.map { it.grams }.sorted()
        assertEquals(listOf(0.0, 0.0), grams)
    }

    @Test
    fun `scaleItem scales only the targeted entry`() = runTest {
        val mealId = seedMeal()
        val entries = repo.mealEntries(mealId)
        val target = entries.first { it.name == "Apple" }
        val other = entries.first { it.name == "Banana" }
        val vm = EditMealViewModel(repo, mealId)

        vm.scaleItem(target.id, 2.0)

        val updated = vm.entries.filter { list ->
            list.firstOrNull { e -> e.id == target.id }?.kcal == 190.0
        }.first()
        assertEquals(190.0, updated.first { it.id == target.id }.kcal, 0.001)
        assertEquals(other.kcal, updated.first { it.id == other.id }.kcal, 0.001)
    }

    @Test
    fun `logToDate creates a new scaled meal group on the target day, leaving original unchanged`() = runTest {
        val mealId = seedMeal()
        val originalEntries = repo.mealEntries(mealId)
        val vm = EditMealViewModel(repo, mealId)

        vm.logToDate(pastDate, 1.5)

        assertEquals(pastDate, vm.loggedToDate.first { it != null })

        val allEntries = repo.foodEntriesForDate(pastDate)
        val newGroupEntries = allEntries.filter { it.mealId != null && it.mealId != mealId }
        assertEquals(2, newGroupEntries.size)
        assertTrue(newGroupEntries.all { it.mealName == "Breakfast" })
        assertNotEquals(mealId, newGroupEntries.first().mealId)
        val kcals = newGroupEntries.map { it.kcal }.sorted()
        assertEquals(listOf(142.5, 157.5), kcals)

        // Original meal untouched.
        val unchanged = repo.mealEntries(mealId)
        assertEquals(originalEntries.map { it.kcal }.sorted(), unchanged.map { it.kcal }.sorted())
    }
}
