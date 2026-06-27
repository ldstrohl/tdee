package com.tdee.app.checkin

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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Unit tests for [CheckinViewModel] (Module 8).
 *
 * Setup mirrors TdeeRepositoryCheckinTest: a male user, 7 days of weight (80→79.3 kg) and matching
 * 2200 kcal/day food, UTC with dayStartHour=0, clock fixed to noon on 2026-06-21. Uses Robolectric
 * + in-memory Room + a fake [CurrentUser].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckinViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository
    private lateinit var vm: CheckinViewModel

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "checkin-vm-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val day0 = LocalDate.of(2026, 6, 13)
    private val weights = listOf(80.0, 79.9, 79.8, 79.6, 79.5, 79.4, 79.3)

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
                proteinGPerKg = 2.0,
                fatPctOfCalories = 0.25,
                dayStartHour = 0,
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = Instant.parse("2026-06-13T08:00:00Z"),
                updatedAt = Instant.parse("2026-06-13T08:00:00Z"),
            )
        )

        weights.forEachIndexed { i, kg ->
            val ts = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant().plusSeconds(8 * 3600)
            db.weightEntryDao().insert(
                WeightEntryEntity(
                    userId = userId,
                    timestamp = ts,
                    weightKg = kg,
                    source = WeightSource.MANUAL,
                    createdAt = ts,
                )
            )
            val tsFood = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant().plusSeconds(13 * 3600)
            db.foodEntryDao().insert(
                FoodEntryEntity(
                    userId = userId,
                    timestamp = tsFood,
                    rawText = "test",
                    name = "Test Food",
                    quantity = 1.0,
                    unit = "serving",
                    grams = 500.0,
                    kcal = 2200.0,
                    proteinG = 150.0,
                    fatG = 61.1,
                    carbG = 263.9,
                    sourceDb = FoodSourceDb.MANUAL,
                    createdAt = tsFood,
                    updatedAt = tsFood,
                )
            )
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

        vm = CheckinViewModel(repo)
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** Wait until the init load has populated the proposal AND prefilled the form. */
    private suspend fun awaitLoaded() {
        vm.proposal.filterNotNull().first()
        vm.form.filter { it.canSave }.first()
    }

    // -----------------------------------------------------------------------
    // 1. Loads + prefills from proposedTargets
    // -----------------------------------------------------------------------

    @Test
    fun `loads proposal and prefills form from proposed targets`() = runTest {
        awaitLoaded()
        val proposal = vm.proposal.value!!
        val form = vm.form.value

        assertEquals(proposal.proposedTargets.calorieTargetKcal.toInt().toString(), form.calorieKcal)
        assertEquals(proposal.proposedTargets.proteinG.toInt().toString(), form.proteinG)
        assertEquals(proposal.proposedTargets.fatG.toInt().toString(), form.fatG)
        assertEquals(proposal.proposedTargets.carbG.toInt().toString(), form.carbG)
        assertTrue("prefilled form should be saveable", form.canSave)
    }

    // -----------------------------------------------------------------------
    // 2. accept() commits the proposed targets (check-in path)
    // -----------------------------------------------------------------------

    @Test
    fun `accept commits the prefilled proposed targets so activeTargets returns them`() = runTest {
        awaitLoaded()
        val proposed = vm.proposal.value!!.proposedTargets

        vm.accept()
        vm.saved.filter { it }.first()

        val active = repo.activeTargets()
        assertEquals(proposed.calorieTargetKcal.toInt(), active.calorieTargetKcal.toInt())
        assertEquals(proposed.proteinG.toInt(), active.proteinG.toInt())
        assertEquals(proposed.fatG.toInt(), active.fatG.toInt())
        assertEquals(proposed.carbG.toInt(), active.carbG.toInt())
    }

    // -----------------------------------------------------------------------
    // 3. Manual-edit path: editing a field then saving persists the edited value
    // -----------------------------------------------------------------------

    @Test
    fun `editing a field then accepting persists the edited value`() = runTest {
        awaitLoaded()

        vm.setCalorie("1750")
        vm.setProtein("180")
        vm.setFat("50")
        vm.setCarb("140")
        assertTrue(vm.form.value.canSave)

        vm.accept()
        vm.saved.filter { it }.first()

        val active = repo.activeTargets()
        assertEquals(1750.0, active.calorieTargetKcal, 0.0)
        assertEquals(180.0, active.proteinG, 0.0)
        assertEquals(50.0, active.fatG, 0.0)
        assertEquals(140.0, active.carbG, 0.0)
    }

    @Test
    fun `accept records the proposal tdee snapshot regardless of edits`() = runTest {
        awaitLoaded()
        val tdee = vm.proposal.value!!.tdeeKcal

        vm.setCalorie("1700")
        vm.accept()
        vm.saved.filter { it }.first()

        val latest = db.targetPeriodDao().getLatest(userId)!!
        assertEquals(tdee, latest.tdeeAtCheckin, 0.0001)
    }

    // -----------------------------------------------------------------------
    // 4. Validation blocks invalid input
    // -----------------------------------------------------------------------

    @Test
    fun `canSave is false for non-numeric input`() = runTest {
        awaitLoaded()
        vm.setCalorie("abc")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false for negative input`() = runTest {
        awaitLoaded()
        vm.setProtein("-5")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false for blank input`() = runTest {
        awaitLoaded()
        vm.setFat("")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `accept is a no-op when a field is invalid`() = runTest {
        awaitLoaded()
        vm.setCalorie("abc")
        vm.accept()
        assertFalse("saved should stay false on invalid input", vm.saved.value)
    }
}
