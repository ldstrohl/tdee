package com.tdee.app.search

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.MealSearchItem
import com.tdee.app.data.MealSearchResult
import com.tdee.app.data.NewFoodItem
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Unit tests for [MealSearchViewModel]: query -> results plumbing (debounce/mapLatest/browse
 * mode) and the three logging paths (saved meal, repeat meal, repeat entry).
 *
 * Uses Robolectric + in-memory Room, a fake [CurrentUser], and a fixed [Clock], mirroring
 * DashboardViewModelTest / TdeeRepositorySearchTest conventions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MealSearchViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val logDate = LocalDate.of(2026, 6, 21)

    private val userId = "meal-search-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.userProfileDao().upsert(makeProfile())

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

    // -----------------------------------------------------------------------
    // 1. Typing a query yields matching results
    // -----------------------------------------------------------------------

    @Test
    fun `typing a query yields matching results`() = runTest {
        repo.addFoodGroup(
            listOf(NewFoodItem("Chicken", 300.0, 30.0, 10.0, 0.0, null)),
            mealName = "Chicken Bowl",
        )
        repo.saveMeal("Green Smoothie", listOf(NewFoodItem("Spinach", 20.0, 2.0, 0.0, 3.0, null)))

        val vm = MealSearchViewModel(repo, logDate, debounceMillis = 0)
        val job = launch { vm.results.collect {} }

        vm.setQuery("chicken")
        // Filter on the specific expected title rather than just "non-empty": the pipeline may
        // transiently emit the blank-query browse-mode result (Green Smoothie) before settling
        // on the "chicken" query's result.
        val results = vm.results.filter { list -> list.any { it.title == "Chicken Bowl" } }.first()

        assertEquals(1, results.size)
        assertEquals("Chicken Bowl", results[0].title)

        job.cancel()
    }

    // -----------------------------------------------------------------------
    // 2. Rapid successive setQuery calls -> only the final query's results land
    // -----------------------------------------------------------------------

    @Test
    fun `rapid successive setQuery calls reflect only the final query`() = runTest {
        repo.addFoodGroup(
            listOf(NewFoodItem("Chicken", 300.0, 30.0, 10.0, 0.0, null)),
            mealName = "Chicken Bowl",
        )
        repo.addFoodGroup(
            listOf(NewFoodItem("Salmon", 350.0, 34.0, 20.0, 0.0, null)),
            mealName = "Salmon Dinner",
        )

        val vm = MealSearchViewModel(repo, logDate, debounceMillis = 0)
        val job = launch { vm.results.collect {} }

        vm.setQuery("s")
        vm.setQuery("sa")
        vm.setQuery("sal")
        vm.setQuery("salmon")

        val results = vm.results.filter { list -> list.any { it.title == "Salmon Dinner" } }.first()

        assertEquals(1, results.size)
        assertEquals("Salmon Dinner", results[0].title)

        job.cancel()
    }

    // -----------------------------------------------------------------------
    // 3. log(Saved, factor) inserts the saved meal's items scaled, onto logDate
    // -----------------------------------------------------------------------

    @Test
    fun `logging a Saved result inserts its items scaled by factor onto logDate`() = runTest {
        val savedMealId = repo.saveMeal(
            "Shake",
            listOf(NewFoodItem("Whey", 120.0, 24.0, 1.0, 2.0, null)),
        )
        val result = MealSearchResult.Saved(
            savedMealId = savedMealId,
            title = "Shake",
            items = listOf(MealSearchItem("Whey", 120.0, 24.0, 1.0, 2.0, null)),
        )

        val vm = MealSearchViewModel(repo, logDate, debounceMillis = 0)
        vm.log(result, factor = 2.0)

        // Wait reactively for the write instead of racing a suspend call fired from
        // viewModelScope.launch (vm.log is fire-and-forget, like SavedMealsViewModel.logToDate).
        val entries = repo.observeFoodEntriesForDate(logDate)
            .filter { list -> list.any { it.name == "Whey" } }
            .first()
        val whey = entries.single { it.name == "Whey" }
        assertEquals(240.0, whey.kcal, 0.001)
        assertEquals(48.0, whey.proteinG, 0.001)
    }

    // -----------------------------------------------------------------------
    // 4. log(LoggedMeal, factor) re-logs the group as a NEW mealId on logDate
    // -----------------------------------------------------------------------

    @Test
    fun `logging a LoggedMeal result creates a new mealId group on logDate`() = runTest {
        val sourceMealId = repo.addFoodGroup(
            listOf(NewFoodItem("Toast", 200.0, 6.0, 4.0, 30.0, null)),
            mealName = "Breakfast",
        )
        val result = MealSearchResult.LoggedMeal(
            mealId = sourceMealId,
            title = "Breakfast",
            items = listOf(MealSearchItem("Toast", 200.0, 6.0, 4.0, 30.0, null)),
            lastLogged = fixedNow,
        )

        val vm = MealSearchViewModel(repo, logDate, debounceMillis = 0)
        vm.log(result, factor = 1.0)

        val entries = repo.observeFoodEntriesForDate(logDate)
            .filter { list -> list.count { it.name == "Toast" } == 2 }
            .first()
        val toastEntries = entries.filter { it.name == "Toast" }
        assertEquals(2, toastEntries.size) // the source entry + the new repeated one
        val newEntry = toastEntries.single { it.mealId != sourceMealId }
        assertNotEquals(sourceMealId, newEntry.mealId)
        assertEquals(200.0, newEntry.kcal, 0.001)
    }

    // -----------------------------------------------------------------------
    // 5. log(LoggedEntry, factor) inserts a standalone copy on logDate
    // -----------------------------------------------------------------------

    @Test
    fun `logging a LoggedEntry result inserts a standalone copy on logDate`() = runTest {
        val entryId = repo.addFood(
            name = "Apple", kcal = 95.0, proteinG = 0.5, fatG = 0.3, carbG = 25.0,
            loggedDate = logDate.minusDays(3),
        )
        val result = MealSearchResult.LoggedEntry(
            entryId = entryId,
            title = "Apple",
            items = listOf(MealSearchItem("Apple", 95.0, 0.5, 0.3, 25.0, null)),
            lastLogged = fixedNow,
        )

        val vm = MealSearchViewModel(repo, logDate, debounceMillis = 0)
        vm.log(result, factor = 1.0)

        val entries = repo.observeFoodEntriesForDate(logDate)
            .filter { list -> list.any { it.name == "Apple" } }
            .first()
        val apple = entries.single { it.name == "Apple" }
        assertEquals(null, apple.mealId)
        assertEquals(95.0, apple.kcal, 0.001)
    }

    // -----------------------------------------------------------------------
    // 5b. logItem(item, factor) inserts a standalone copy of the item scaled, onto logDate
    // -----------------------------------------------------------------------

    @Test
    fun `logItem inserts the item scaled by factor onto logDate and sets justLogged`() = runTest {
        val item = MealSearchItem("Rice", 200.0, 4.0, 0.5, 44.0, 150.0)

        val vm = MealSearchViewModel(repo, logDate, debounceMillis = 0)
        assertEquals(null, vm.justLogged.value)

        vm.logItem(item, factor = 1.5)

        val entries = repo.observeFoodEntriesForDate(logDate)
            .filter { list -> list.any { it.name == "Rice" } }
            .first()
        val rice = entries.single { it.name == "Rice" }
        assertEquals(null, rice.mealId)
        assertEquals(300.0, rice.kcal, 0.001)
        assertEquals(6.0, rice.proteinG, 0.001)
        assertEquals(0.75, rice.fatG, 0.001)
        assertEquals(66.0, rice.carbG, 0.001)
        assertEquals(225.0, rice.grams, 0.001)

        val justLogged = vm.justLogged.filter { it != null }.first()
        assertEquals(item.name, justLogged)
    }

    // -----------------------------------------------------------------------
    // 6. justLogged becomes the logged result's key
    // -----------------------------------------------------------------------

    @Test
    fun `justLogged becomes the logged result's key`() = runTest {
        val savedMealId = repo.saveMeal(
            "Shake",
            listOf(NewFoodItem("Whey", 120.0, 24.0, 1.0, 2.0, null)),
        )
        val result = MealSearchResult.Saved(
            savedMealId = savedMealId,
            title = "Shake",
            items = listOf(MealSearchItem("Whey", 120.0, 24.0, 1.0, 2.0, null)),
        )

        val vm = MealSearchViewModel(repo, logDate, debounceMillis = 0)
        assertEquals(null, vm.justLogged.value)

        vm.log(result, factor = 1.0)
        val justLogged = vm.justLogged.filter { it != null }.first()

        assertEquals(result.key, justLogged)
    }

    // -----------------------------------------------------------------------
    // 7. Blank query returns saved meals (browse mode)
    // -----------------------------------------------------------------------

    @Test
    fun `blank query returns saved meals in browse mode`() = runTest {
        repo.saveMeal("First", listOf(NewFoodItem("A", 100.0, 0.0, 0.0, 0.0, null)))
        repo.saveMeal("Second", listOf(NewFoodItem("B", 200.0, 0.0, 0.0, 0.0, null)))

        val vm = MealSearchViewModel(repo, logDate, debounceMillis = 0)
        val job = launch { vm.results.collect {} }

        // query starts blank; trigger a fresh emission through the pipeline.
        vm.setQuery("")
        val results = vm.results.filter { it.size == 2 }.first()

        assertTrue(results.all { it is MealSearchResult.Saved })

        job.cancel()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeProfile() = UserProfileEntity(
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
}
