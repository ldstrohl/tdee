package com.tdee.app.dashboard

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.data.FoodSourceDb
import com.tdee.app.data.NewFoodItem
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.data.WeightEntryEntity
import com.tdee.app.data.WeightSource
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import com.tdee.domain.kgToLb
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Unit tests for [DashboardViewModel].
 *
 * Uses Robolectric + in-memory Room. A fixed clock (2026-06-21T12:00:00Z, UTC) and a
 * fake [CurrentUser] keep results deterministic.
 *
 * Seed data mirrors the pattern established in TdeeRepositoryTest: 7 weight entries
 * with a slight downward trend, and matching food entries on past log-days. Today's
 * food is injected separately per test.
 *
 * Tests that check consumed-kcal totals pass [fixedDay] as the VM's initialDate so
 * that [DashboardViewModel.dayFoods] observes the same log-day the fixed clock uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    // Clock is noon on 2026-06-21, the day after the last weight/food entry.
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val fixedDay = LocalDate.of(2026, 6, 21)

    private val userId = "dashboard-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    // Weight data: 7 days ending 2026-06-19, gentle downward trend.
    private val day0 = LocalDate.of(2026, 6, 13)
    private val weights = listOf(80.0, 79.9, 79.8, 79.6, 79.5, 79.4, 79.3)
    private val dailyKcal = 2200.0

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.userProfileDao().upsert(makeProfile(goalRateKgPerWeek = -0.25))

        // Seed 7 days of weight + food (all on past log-days, not today).
        weights.forEachIndexed { i, kg ->
            val ts = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant()
                .plusSeconds(8 * 3600) // 08:00 UTC each day
            db.weightEntryDao().insert(
                WeightEntryEntity(
                    userId = userId,
                    timestamp = ts,
                    weightKg = kg,
                    source = WeightSource.MANUAL,
                    createdAt = ts,
                )
            )
            val tsFood = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant()
                .plusSeconds(13 * 3600) // 13:00 UTC each day
            db.foodEntryDao().insert(makeFoodEntry(tsFood, kcal = dailyKcal))
        }

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
    // 1. Reaches Loaded state with seeded data
    // -----------------------------------------------------------------------

    @Test
    fun `reaches Loaded state with seeded profile and weight data`() = runTest {
        val vm = DashboardViewModel(repo)

        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertTrue("TDEE should be positive", loaded.tdeeKcal > 0)
        assertTrue("TDEE should be a plausible kcal value (>500)", loaded.tdeeKcal > 500)
    }

    // -----------------------------------------------------------------------
    // 2. calibrating == true with 7 days of data (window = 14)
    // -----------------------------------------------------------------------

    @Test
    fun `calibrating is true with only 7 days of data (window=14)`() = runTest {
        val vm = DashboardViewModel(repo)

        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertTrue(
            "Should be calibrating when data < full tdeeWindowDays (14)",
            loaded.calibrating,
        )
    }

    // -----------------------------------------------------------------------
    // 3. kg → lb conversion
    // -----------------------------------------------------------------------

    @Test
    fun `trend weight is converted from kg to lb (kg × 2_2046226)`() = runTest {
        val vm = DashboardViewModel(repo)

        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        // The EMA trend is somewhere around 79 kg given our seed data.
        // Assert that the displayed lb value is the correct conversion of whatever kg the
        // engine returns — derive the expected lb from the repo directly.
        val trendKg = repo.currentTrendKg()
        val expectedLb = kgToLb(trendKg)

        assertEquals(
            "trendWeightLb should equal kgToLb(currentTrendKg())",
            expectedLb,
            loaded.trendWeightLb,
            0.0001,
        )
        // Also sanity-check the value is in a plausible pound range (~170–180 lb for ~79 kg).
        assertTrue("Trend weight in lb should be > 100", loaded.trendWeightLb > 100.0)
        assertTrue("Trend weight in lb should be < 300", loaded.trendWeightLb < 300.0)
    }

    // -----------------------------------------------------------------------
    // 4. Today's consumed kcal matches seeded food
    // -----------------------------------------------------------------------

    @Test
    fun `todayConsumedKcal is null when no food logged today`() = runTest {
        // No food on today's log-day (2026-06-21) in default setup.
        val vm = DashboardViewModel(repo, fixedDay)

        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertNull("No food today → todayConsumedKcal should be null", loaded.todayConsumedKcal)
    }

    @Test
    fun `todayConsumedKcal matches sum of seeded today food entries`() = runTest {
        // Seed two food entries on today's log-day (2026-06-21).
        val t1 = Instant.parse("2026-06-21T08:00:00Z")
        val t2 = Instant.parse("2026-06-21T13:00:00Z")
        db.foodEntryDao().insert(makeFoodEntry(t1, kcal = 600.0))
        db.foodEntryDao().insert(makeFoodEntry(t2, kcal = 800.0))

        val vm = DashboardViewModel(repo, fixedDay)

        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertNotNull(loaded.todayConsumedKcal)
        assertEquals(
            "Consumed kcal should sum the two today entries (600+800=1400)",
            1400,
            loaded.todayConsumedKcal,
        )
    }

    // -----------------------------------------------------------------------
    // 5. Calorie target is below TDEE for a deficit goal
    // -----------------------------------------------------------------------

    @Test
    fun `calorieTargetKcal is below TDEE estimate for deficit goal`() = runTest {
        // Default profile has goalRateKgPerWeek = -0.25 (cut).
        val vm = DashboardViewModel(repo)

        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertTrue(
            "Calorie target (${loaded.calorieTargetKcal}) should be below TDEE " +
                "(${loaded.tdeeKcal}) for a deficit goal",
            loaded.calorieTargetKcal < loaded.tdeeKcal,
        )
    }

    // -----------------------------------------------------------------------
    // 6. observeProfile() flow — null before save, non-null after
    // -----------------------------------------------------------------------

    @Test
    fun `observeProfile emits null before profile exists and non-null after save`() = runTest {
        // Use a fresh db with no profile.
        val freshDb = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        val freshUserId = "fresh-routing-user"
        val freshRepo = TdeeRepository(
            profileDao = freshDb.userProfileDao(),
            weightDao = freshDb.weightEntryDao(),
            foodDao = freshDb.foodEntryDao(),
            targetDao = freshDb.targetPeriodDao(),
            trendCacheDao = freshDb.weightTrendCacheDao(),
            savedMealDao = freshDb.savedMealDao(),
            currentUser = CurrentUser { freshUserId },
            zone = zone,
            clock = fixedClock,
        )

        // Before save: emits null.
        val beforeSave = freshRepo.observeProfile().first()
        assertNull("observeProfile should emit null before any profile is saved", beforeSave)

        // Seed a profile and seed weight.
        freshRepo.saveProfileAndSeedWeight(
            makeProfile(goalRateKgPerWeek = -0.25).copy(userId = freshUserId),
            seedWeightKg = 80.0,
        )

        // After save: emits non-null profile.
        val afterSave = freshRepo.observeProfile()
            .filter { it != null }
            .first()
        assertNotNull("observeProfile should emit non-null after profile is saved", afterSave)
        assertEquals(freshUserId, afterSave!!.userId)

        freshDb.close()
    }

    // -----------------------------------------------------------------------
    // 7. consumedTotals — per-macro sums from dayFoods
    // -----------------------------------------------------------------------

    @Test
    fun `consumedTotals is zero when no food logged today`() = runTest {
        val vm = DashboardViewModel(repo, fixedDay)

        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertEquals(ConsumedTotals.Empty, loaded.consumedTotals)
    }

    @Test
    fun `consumedTotals matches per-macro sums of today food entries`() = runTest {
        // Seed two food entries on today's log-day (2026-06-21).
        // makeFoodEntry uses proteinG=50, fatG=20, carbG=80 per entry.
        val t1 = Instant.parse("2026-06-21T08:00:00Z")
        val t2 = Instant.parse("2026-06-21T13:00:00Z")
        db.foodEntryDao().insert(makeFoodEntry(t1, kcal = 600.0))
        db.foodEntryDao().insert(makeFoodEntry(t2, kcal = 800.0))

        val vm = DashboardViewModel(repo, fixedDay)

        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        // Each entry has proteinG=50, fatG=20, carbG=80, so totals are 2×.
        assertEquals(100, loaded.consumedTotals.proteinG)
        assertEquals(40, loaded.consumedTotals.fatG)
        assertEquals(160, loaded.consumedTotals.carbG)
        assertEquals(1400, loaded.consumedTotals.kcal)
    }

    @Test
    fun `consumedTotals updates reactively after food entry is added`() = runTest {
        // VM views fixedDay (2026-06-21); repo.addFood uses clock.instant() = same day.
        val vm = DashboardViewModel(repo, fixedDay)

        // Wait for initial loaded state (no food today).
        val initial = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded
        assertEquals(ConsumedTotals.Empty, initial.consumedTotals)

        // Add a food entry via the repo directly (simulates what AddFoodViewModel does).
        repo.addFood(
            name = "Test",
            kcal = 500.0,
            proteinG = 30.0,
            fatG = 10.0,
            carbG = 60.0,
        )

        // The reactive state should now reflect the new entry.
        val updated = vm.state
            .filter { s ->
                s is DashboardUiState.Loaded && s.consumedTotals.kcal > 0
            }
            .first() as DashboardUiState.Loaded

        assertEquals(500, updated.consumedTotals.kcal)
        assertEquals(30, updated.consumedTotals.proteinG)
        assertEquals(10, updated.consumedTotals.fatG)
        assertEquals(60, updated.consumedTotals.carbG)
        assertEquals(500, updated.todayConsumedKcal)
    }

    @Test
    fun `todayConsumedKcal becomes null again after all food entries are deleted`() = runTest {
        // Seed one today food entry.
        val t1 = Instant.parse("2026-06-21T08:00:00Z")
        val insertedId = db.foodEntryDao().insert(makeFoodEntry(t1, kcal = 400.0))

        val vm = DashboardViewModel(repo, fixedDay)

        // Wait for entry to appear.
        val withFood = vm.state
            .filter { s -> s is DashboardUiState.Loaded && s.todayConsumedKcal != null }
            .first() as DashboardUiState.Loaded
        assertEquals(400, withFood.todayConsumedKcal)

        // Soft-delete the entry.
        vm.deleteFood(insertedId)

        // Consumed should go back to null.
        val afterDelete = vm.state
            .filter { s -> s is DashboardUiState.Loaded && s.todayConsumedKcal == null }
            .first() as DashboardUiState.Loaded
        assertNull(afterDelete.todayConsumedKcal)
        assertEquals(ConsumedTotals.Empty, afterDelete.consumedTotals)
    }

    // -----------------------------------------------------------------------
    // 8. Active targets + checkinDue (Module 8)
    // -----------------------------------------------------------------------

    @Test
    fun `targets reflect the active period after a commit, not the live proposed value`() = runTest {
        // Commit an arbitrary target period; the dashboard should use it verbatim.
        val committed = com.tdee.domain.Targets(
            calorieTargetKcal = 1850.0,
            proteinG = 175.0,
            fatG = 55.0,
            carbG = 150.0,
        )
        repo.commitTargets(committed, tdeeAtCheckinKcal = 2400.0)

        val vm = DashboardViewModel(repo)
        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertEquals(1850, loaded.calorieTargetKcal)
        assertEquals(committed, loaded.macroTargets)
    }

    // -----------------------------------------------------------------------
    // 9. deleteMeal
    // -----------------------------------------------------------------------

    @Test
    fun `deleteMeal soft-deletes all entries in the group`() = runTest {
        // Insert a meal group on today's log-day via the repo (clock = 2026-06-21).
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            )
        )

        val vm = DashboardViewModel(repo, fixedDay)

        // Wait for the two entries to appear in the day's food.
        val withFood = vm.dayFoods
            .filter { it.size == 2 }
            .first()
        assertEquals(2, withFood.size)

        vm.deleteMeal(mealId)

        // After delete, the food list should be empty.
        val afterDelete = vm.dayFoods
            .filter { it.isEmpty() }
            .first()
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun `deleteMeal does not affect entries from other groups`() = runTest {
        val mealId1 = repo.addFoodGroup(
            listOf(NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null))
        )
        repo.addFoodGroup(
            listOf(NewFoodItem("Chicken", 250.0, 30.0, 5.0, 0.0, null))
        )

        val vm = DashboardViewModel(repo, fixedDay)

        // Wait for two entries to appear.
        vm.dayFoods.filter { it.size == 2 }.first()

        vm.deleteMeal(mealId1)

        // Only the second group's entry should remain.
        val afterDelete = vm.dayFoods
            .filter { it.size == 1 }
            .first()
        assertEquals(1, afterDelete.size)
        assertEquals("Chicken", afterDelete[0].name)
    }

    @Test
    fun `checkinDue is true with no period and false right after a commit`() = runTest {
        // No period yet → due.
        val vmDue = DashboardViewModel(repo)
        val due = vmDue.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded
        assertTrue("checkinDue should be true with no period yet", due.checkinDue)

        // After committing a period dated today → not due.
        repo.commitTargets(repo.proposedTargets(), tdeeAtCheckinKcal = 2400.0)
        val vmNotDue = DashboardViewModel(repo)
        val notDue = vmNotDue.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded
        assertTrue("checkinDue should be false right after a commit", !notDue.checkinDue)
    }

    // -----------------------------------------------------------------------
    // 11. Weigh-in reminder — daysSinceLastWeighIn
    // -----------------------------------------------------------------------

    @Test
    fun `daysSinceLastWeighIn reflects a 5-day-old weigh-in (reminder should show)`() = runTest {
        val freshDb = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val freshUserId = "reminder-user-5-day"
        val freshRepo = TdeeRepository(
            profileDao = freshDb.userProfileDao(),
            weightDao = freshDb.weightEntryDao(),
            foodDao = freshDb.foodEntryDao(),
            targetDao = freshDb.targetPeriodDao(),
            trendCacheDao = freshDb.weightTrendCacheDao(),
            savedMealDao = freshDb.savedMealDao(),
            currentUser = CurrentUser { freshUserId },
            zone = zone,
            clock = fixedClock,
        )
        freshDb.userProfileDao().upsert(makeProfile(goalRateKgPerWeek = -0.25).copy(userId = freshUserId))
        val weighInTs = fixedNow.minus(5, java.time.temporal.ChronoUnit.DAYS)
        freshDb.weightEntryDao().insert(
            WeightEntryEntity(
                userId = freshUserId,
                timestamp = weighInTs,
                weightKg = 80.0,
                source = WeightSource.MANUAL,
                createdAt = weighInTs,
            )
        )

        val vm = DashboardViewModel(freshRepo)
        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertEquals(5L, loaded.daysSinceLastWeighIn)

        freshDb.close()
    }

    @Test
    fun `daysSinceLastWeighIn reflects a 1-day-old weigh-in (reminder should not show)`() = runTest {
        val freshDb = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val freshUserId = "reminder-user-1-day"
        val freshRepo = TdeeRepository(
            profileDao = freshDb.userProfileDao(),
            weightDao = freshDb.weightEntryDao(),
            foodDao = freshDb.foodEntryDao(),
            targetDao = freshDb.targetPeriodDao(),
            trendCacheDao = freshDb.weightTrendCacheDao(),
            savedMealDao = freshDb.savedMealDao(),
            currentUser = CurrentUser { freshUserId },
            zone = zone,
            clock = fixedClock,
        )
        freshDb.userProfileDao().upsert(makeProfile(goalRateKgPerWeek = -0.25).copy(userId = freshUserId))
        val weighInTs = fixedNow.minus(1, java.time.temporal.ChronoUnit.DAYS)
        freshDb.weightEntryDao().insert(
            WeightEntryEntity(
                userId = freshUserId,
                timestamp = weighInTs,
                weightKg = 80.0,
                source = WeightSource.MANUAL,
                createdAt = weighInTs,
            )
        )

        val vm = DashboardViewModel(freshRepo)
        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertEquals(1L, loaded.daysSinceLastWeighIn)

        freshDb.close()
    }

    @Test
    fun `daysSinceLastWeighIn is null with no weight samples at all`() = runTest {
        val freshDb = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val freshUserId = "reminder-user-no-samples"
        val freshRepo = TdeeRepository(
            profileDao = freshDb.userProfileDao(),
            weightDao = freshDb.weightEntryDao(),
            foodDao = freshDb.foodEntryDao(),
            targetDao = freshDb.targetPeriodDao(),
            trendCacheDao = freshDb.weightTrendCacheDao(),
            savedMealDao = freshDb.savedMealDao(),
            currentUser = CurrentUser { freshUserId },
            zone = zone,
            clock = fixedClock,
        )
        freshDb.userProfileDao().upsert(makeProfile(goalRateKgPerWeek = -0.25).copy(userId = freshUserId))

        val vm = DashboardViewModel(freshRepo)
        val loaded = vm.state
            .filter { it is DashboardUiState.Loaded }
            .first() as DashboardUiState.Loaded

        assertNull("No weight samples → daysSinceLastWeighIn should be null", loaded.daysSinceLastWeighIn)

        freshDb.close()
    }

    // -----------------------------------------------------------------------
    // 10. Date navigation — selectedDate / prevDay / nextDay / dayFoods
    // -----------------------------------------------------------------------

    @Test
    fun `setSelectedDate makes dayFoods reflect the chosen day`() = runTest {
        // The seed setup inserts food at 13:00 UTC for each of day0..day0+6.
        // Navigate the VM to day0 and verify dayFoods shows that day's entry.
        val vm = DashboardViewModel(repo, fixedDay, clock = { fixedDay })

        vm.setSelectedDate(day0)

        val foods = vm.dayFoods
            .filter { it.isNotEmpty() }
            .first()
        assertEquals(1, foods.size)
        assertEquals(dailyKcal, foods[0].kcal, 0.001)
    }

    @Test
    fun `prevDay moves selectedDate back one day`() = runTest {
        val vm = DashboardViewModel(repo, fixedDay, clock = { fixedDay })

        vm.prevDay()

        assertEquals(fixedDay.minusDays(1), vm.selectedDate.value)
    }

    @Test
    fun `prevDay makes dayFoods reflect the prior day`() = runTest {
        // The day before fixedDay is 2026-06-20, which has seed food (day0+7 = 2026-06-20).
        // Actually: day0 = 2026-06-13; seed range is day0..day0+6 = 2026-06-13..2026-06-19.
        // fixedDay = 2026-06-21; prevDay → 2026-06-20 (no seed food).
        // Let's use setSelectedDate to a day we know has food.
        val targetDay = day0.plusDays(2) // 2026-06-15 — has seed food
        val vm = DashboardViewModel(repo, fixedDay, clock = { fixedDay })

        vm.setSelectedDate(targetDay.plusDays(1)) // navigate to 2026-06-16
        vm.prevDay()                              // → 2026-06-15

        assertEquals(targetDay, vm.selectedDate.value)

        val foods = vm.dayFoods
            .filter { it.isNotEmpty() }
            .first()
        assertEquals(1, foods.size)
    }

    @Test
    fun `nextDay clamps to today and does not go into the future`() = runTest {
        // VM's "today" is fixedDay (2026-06-21). nextDay() from today should stay at today.
        val vm = DashboardViewModel(repo, fixedDay, clock = { fixedDay })
        assertEquals(fixedDay, vm.selectedDate.value)

        vm.nextDay()

        assertEquals(fixedDay, vm.selectedDate.value)
    }

    @Test
    fun `nextDay advances when currently viewing a past day`() = runTest {
        val vm = DashboardViewModel(repo, fixedDay, clock = { fixedDay })
        vm.setSelectedDate(fixedDay.minusDays(2))

        vm.nextDay()

        assertEquals(fixedDay.minusDays(1), vm.selectedDate.value)
    }

    @Test
    fun `goToToday resets selectedDate back to today`() = runTest {
        val vm = DashboardViewModel(repo, fixedDay, clock = { fixedDay })
        vm.setSelectedDate(day0) // navigate to a past day
        assertEquals(day0, vm.selectedDate.value)

        vm.goToToday()

        assertEquals(fixedDay, vm.selectedDate.value)
    }

    @Test
    fun `nextDay succeeds past the old today once the clock advances a day`() = runTest {
        // The clock isn't frozen at construction — a mutable clock lets navigation follow
        // the real calendar date across a midnight rollover instead of getting stuck.
        var clockDay = fixedDay
        val vm = DashboardViewModel(repo, fixedDay, clock = { clockDay })
        assertEquals(fixedDay, vm.selectedDate.value)

        vm.nextDay() // still clamped to fixedDay — the clock hasn't advanced yet
        assertEquals(fixedDay, vm.selectedDate.value)

        clockDay = fixedDay.plusDays(1)
        vm.nextDay()

        assertEquals(fixedDay.plusDays(1), vm.selectedDate.value)
    }

    // -----------------------------------------------------------------------
    // 12. Error state — load failure surfaces a message and retry recovers it
    // -----------------------------------------------------------------------

    @Test
    fun `load failure surfaces an Error state instead of hanging on Loading`() = runTest {
        // No profile exists for this user, so dashboardSnapshot() throws "No user profile".
        val noProfileRepo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            savedMealDao = db.savedMealDao(),
            currentUser = CurrentUser { "no-such-user" },
            zone = zone,
            clock = fixedClock,
        )
        val vm = DashboardViewModel(noProfileRepo)

        val error = vm.state.filter { it is DashboardUiState.Error }.first() as DashboardUiState.Error

        assertEquals("No user profile", error.message)
    }

    @Test
    fun `reload after a fixed error reaches Loaded state`() = runTest {
        val userId2 = "retry-user"
        val noProfileRepo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            savedMealDao = db.savedMealDao(),
            currentUser = CurrentUser { userId2 },
            zone = zone,
            clock = fixedClock,
        )
        val vm = DashboardViewModel(noProfileRepo)
        vm.state.filter { it is DashboardUiState.Error }.first()

        // Fix the underlying problem, then retry.
        db.userProfileDao().upsert(makeProfile(goalRateKgPerWeek = -0.25).copy(userId = userId2))
        vm.reload()

        val loaded = vm.state.filter { it is DashboardUiState.Loaded }.first()

        assertTrue("Retry after fixing the error should reach Loaded", loaded is DashboardUiState.Loaded)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeProfile(goalRateKgPerWeek: Double) = UserProfileEntity(
        userId = userId,
        sex = Sex.MALE,
        birthYear = 1990,
        heightCm = 175.0,
        activityLevel = ActivityLevel.MODERATE,
        goalRateKgPerWeek = goalRateKgPerWeek,
        goalWeightKg = 75.0,
        proteinGPerKg = 2.0,
        fatPctOfCalories = 0.25,
        dayStartHour = 0,
        smoothingWindowDays = 14,
        tdeeWindowDays = 14,
        createdAt = Instant.parse("2026-06-13T08:00:00Z"),
        updatedAt = Instant.parse("2026-06-13T08:00:00Z"),
    )

    private fun makeFoodEntry(timestamp: Instant, kcal: Double) = FoodEntryEntity(
        userId = userId,
        timestamp = timestamp,
        rawText = "test",
        name = "Test Food",
        quantity = 1.0,
        unit = "serving",
        grams = 200.0,
        kcal = kcal,
        proteinG = 50.0,
        fatG = 20.0,
        carbG = 80.0,
        sourceDb = FoodSourceDb.MANUAL,
        createdAt = timestamp,
        updatedAt = timestamp,
    )
}
