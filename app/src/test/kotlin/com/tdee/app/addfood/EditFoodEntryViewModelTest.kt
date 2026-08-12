package com.tdee.app.addfood

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
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
import org.junit.Assert.assertNull
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
 * Tests for [EditFoodEntryViewModel.logToDate], which re-logs the stored entry as a standalone
 * copy on an arbitrary date via [TdeeRepository.repeatEntry], and [EditFoodEntryViewModel.save].
 *
 * Uses an in-memory Room database, a fake [CurrentUser], and a fixed [Clock].
 * Fixed "now" = 2026-06-21T12:00:00Z -> log-day 2026-06-21 (dayStartHour = 0, UTC).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditFoodEntryViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val pastDate = LocalDate.of(2026, 6, 15)

    private val userId = "edit-food-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

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
        // Let the ViewModel coroutines this test started finish before the dispatcher goes away —
        // one still live at the next `Dispatchers.setMain` fails an unrelated test with
        // "Dispatchers.Main is used concurrently with setting it" (see DashboardViewModelTest).
        testDispatcher.scheduler.advanceUntilIdle()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `logToDate inserts a scaled standalone copy on the target day and leaves the original`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        val vm = EditFoodEntryViewModel(repo, original.id)

        vm.logToDate(pastDate, 0.5)
        vm.loggedToDate.first { it != null }

        val targetEntries = repo.foodEntriesForDate(pastDate)
        assertEquals(1, targetEntries.size)
        assertEquals(150.0, targetEntries[0].kcal, 0.001)
        assertNull(targetEntries[0].mealId)
        assertEquals(pastDate, vm.loggedToDate.value)

        val todayEntries = repo.foodEntriesForDate(LocalDate.now(fixedClock))
        assertEquals(1, todayEntries.size)
        assertEquals(300.0, todayEntries[0].kcal, 0.001)
    }

    @Test
    fun `logToDate converts the confirmed absolute factor to a relative factor`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        // Simulate a stored entry that's already scaled 2x from its original serving.
        repo.repeatEntry(original.id, factor = 2.0)
        val scaled = db.foodEntryDao().getActive(userId).first { it.scaleFactor == 2.0 }
        assertEquals(600.0, scaled.kcal, 0.001)

        val vm = EditFoodEntryViewModel(repo, scaled.id)
        vm.state.first { it.scaleFactor == 2.0 }

        // User confirms "1" (absolute, i.e. back to the original serving) -> relative 0.5.
        vm.logToDate(pastDate, 1.0)
        vm.loggedToDate.first { it != null }

        val targetEntries = repo.foodEntriesForDate(pastDate)
        assertEquals(1, targetEntries.size)
        assertEquals(1.0, targetEntries[0].scaleFactor, 0.001)
        assertEquals(300.0, targetEntries[0].kcal, 0.001)
    }

    @Test
    fun `logToDate converts a confirmed absolute factor of 3 to relative 1_5 when stored factor is 2`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        repo.repeatEntry(original.id, factor = 2.0)
        val scaled = db.foodEntryDao().getActive(userId).first { it.scaleFactor == 2.0 }

        val vm = EditFoodEntryViewModel(repo, scaled.id)
        vm.state.first { it.scaleFactor == 2.0 }

        vm.logToDate(pastDate, 3.0)
        vm.loggedToDate.first { it != null }

        val targetEntries = repo.foodEntriesForDate(pastDate)
        assertEquals(1, targetEntries.size)
        // Resulting scaleFactor is cumulative vs. the native serving: 2 (stored) * 1.5 (relative) = 3.
        assertEquals(3.0, targetEntries[0].scaleFactor, 0.001)
        assertEquals(900.0, targetEntries[0].kcal, 0.001)
    }

    @Test
    fun `logToDate uses the STORED scale, not an unsaved live edit of the scale field`() = runTest {
        // Stored entry: native 300 kcal, scaleFactor 1 (never saved as anything else).
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        val vm = EditFoodEntryViewModel(repo, original.id)
        vm.state.first { it.name == "Oats" }

        // User edits the scale field to 2 (displayed kcal becomes 600) but does NOT save.
        vm.setScale("2")
        assertEquals("600", vm.state.value.kcal)

        // User picks the "2x" preset in the log-to-another-day dialog, matching what's on screen.
        vm.logToDate(pastDate, 2.0)
        vm.loggedToDate.first { it != null }

        val targetEntries = repo.foodEntriesForDate(pastDate)
        assertEquals(1, targetEntries.size)
        // Should log what the user saw on screen (600 kcal). The relative factor must be computed
        // against the STORED scaleFactor (1.0), not the live-edited, unsaved field (2.0).
        assertEquals(600.0, targetEntries[0].kcal, 0.001)
    }

    @Test
    fun `load of an entry with scaleFactor 2 shows scale 2 and the stored scaled macros`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        repo.repeatEntry(original.id, factor = 2.0)
        val scaled = db.foodEntryDao().getActive(userId).first { it.scaleFactor == 2.0 }

        val vm = EditFoodEntryViewModel(repo, scaled.id)
        vm.state.first { it.scale == "2" }

        val s = vm.state.value
        assertEquals("600.0", s.kcal)
        assertEquals("20.0", s.proteinG)
        assertEquals("10.0", s.fatG)
        assertEquals("110.0", s.carbG)
    }

    @Test
    fun `setting scale to 1_5 rescales kcal and macros from the native base`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        val vm = EditFoodEntryViewModel(repo, original.id)
        vm.state.first { it.name == "Oats" }

        vm.setScale("1.5")

        val s = vm.state.value
        assertEquals("1.5", s.scale)
        assertEquals("450", s.kcal)
        assertEquals("15", s.proteinG)
        assertEquals("7.5", s.fatG)
        assertEquals("82.5", s.carbG)
    }

    @Test
    fun `editing kcal then changing scale rescales from the corrected base`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        val vm = EditFoodEntryViewModel(repo, original.id)
        vm.state.first { it.name == "Oats" }

        // Correct the kcal at the current (native, ×1) scale -> new base kcal = 320.
        vm.setKcal("320")
        vm.setScale("2")

        assertEquals("640", vm.state.value.kcal)
    }

    @Test
    fun `invalid scale blocks save`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        val vm = EditFoodEntryViewModel(repo, original.id)
        vm.state.first { it.name == "Oats" }

        for (bad in listOf("", "0", "-1", "abc")) {
            vm.setScale(bad)
            assertEquals(false, vm.state.value.canSave)
        }
    }

    @Test
    fun `save persists the edited scaleFactor`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        val vm = EditFoodEntryViewModel(repo, original.id)
        vm.state.first { it.name == "Oats" }

        vm.setScale("1.5")
        vm.save()
        vm.saved.first { it }

        val updated = db.foodEntryDao().getById(original.id)
        assertEquals(1.5, updated?.scaleFactor ?: 0.0, 0.001)
        assertEquals(450.0, updated?.kcal ?: 0.0, 0.001)
    }

    @Test
    fun `save writes edited fields back to the repo`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
        )
        val original = db.foodEntryDao().getActive(userId).first()
        val vm = EditFoodEntryViewModel(repo, original.id)
        vm.state.first { it.name == "Oats" }

        vm.setName("Steel-cut oats")
        vm.setKcal("320")
        vm.save()
        vm.saved.first { it }

        val updated = db.foodEntryDao().getById(original.id)
        assertEquals("Steel-cut oats", updated?.name)
        assertEquals(320.0, updated?.kcal ?: 0.0, 0.001)
    }
}
