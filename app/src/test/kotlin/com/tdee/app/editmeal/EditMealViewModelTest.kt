package com.tdee.app.editmeal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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

    /**
     * ViewModels created by a test. Each one starts eager `stateIn` collectors on its
     * viewModelScope; if they are still live when the next test calls `Dispatchers.setMain`,
     * the Main dispatcher is being reset while in use and an unrelated test fails with
     * "Dispatchers.Main is used concurrently with setting it". Clearing them in teardown
     * cancels those scopes, so tests stop leaking coroutines into each other.
     */
    private val viewModelStore = ViewModelStore()
    private var viewModelKey = 0

    @Suppress("UNCHECKED_CAST")
    private fun viewModel(mealId: String): EditMealViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EditMealViewModel(repo, mealId) as T
        }
        return ViewModelProvider(viewModelStore, factory)[
            "vm-${viewModelKey++}", EditMealViewModel::class.java,
        ]
    }

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries()
            // Room runs queries and invalidation-tracker callbacks on its own executors, so a
            // Flow emission can dispatch to Main on a thread the test scheduler cannot drain —
            // after the test ended, colliding with the next test's Dispatchers.setMain. Running
            // both executors inline keeps all of that on the test thread.
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

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
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun teardown() {
        // Order matters. Cancel the ViewModel scopes first, then let the dispatcher drain so
        // those cancellations actually unwind, and only then close the database. Closing it
        // while a collector is still mid-query throws from a coroutine nobody is awaiting, which
        // resurfaces as an unrelated test failing with UncaughtExceptionsBeforeTest.
        viewModelStore.clear()
        testDispatcher.scheduler.advanceUntilIdle()
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
        val vm = viewModel(mealId)

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
        val vm = viewModel(mealId)

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
        val vm = viewModel(mealId)

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
