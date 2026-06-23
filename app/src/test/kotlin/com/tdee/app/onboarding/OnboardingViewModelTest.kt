package com.tdee.app.onboarding

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.TdeeRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Unit tests for [OnboardingViewModel]: validation, unit conversions, and persistence.
 *
 * Uses Robolectric + in-memory Room so [TdeeRepository] is the real implementation.
 * A fixed clock (2026-06-21T12:00:00Z) and a fake [CurrentUser] keep results deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository
    private lateinit var vm: OnboardingViewModel

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "onboarding-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        repo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            currentUser = fakeCurrentUser,
            zone = zone,
            clock = fixedClock,
        )

        vm = OnboardingViewModel(repo)
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // 1. Validation — canSave
    // -----------------------------------------------------------------------

    @Test
    fun `canSave is false on blank form`() {
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when sex is missing`() {
        fillRequiredFields(sex = false)
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when birthYear is missing`() {
        fillRequiredFields(birthYear = false)
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when birthYear is out of range`() {
        fillRequiredFields()
        vm.setBirthYear("1800")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when heightFt is missing`() {
        fillRequiredFields(heightFt = false)
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when heightIn is missing`() {
        fillRequiredFields(heightIn = false)
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when currentWeightLb is missing`() {
        fillRequiredFields(currentWeightLb = false)
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when activityLevel is missing`() {
        fillRequiredFields(activityLevel = false)
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is true when all required fields are valid`() {
        fillRequiredFields()
        assertTrue(vm.form.value.canSave)
    }

    @Test
    fun `canSave is true with MAINTAIN goal (no rate needed from user)`() {
        fillRequiredFields()
        vm.setGoal(GoalSelection.MAINTAIN)
        assertTrue(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when CUT rate is blank`() {
        fillRequiredFields()
        vm.setGoal(GoalSelection.CUT)
        vm.setGoalRateLbPerWeek("")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is true when CUT rate is provided`() {
        fillRequiredFields()
        vm.setGoal(GoalSelection.CUT)
        vm.setGoalRateLbPerWeek("1.0")
        assertTrue(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when fat pct override is out of range`() {
        fillRequiredFields()
        vm.setFatPct("1.5")
        assertFalse(vm.form.value.canSave)
    }

    // -----------------------------------------------------------------------
    // 2. Unit conversion
    // -----------------------------------------------------------------------

    @Test
    fun `lb to kg conversion is correct`() {
        assertEquals(72.5748, lbToKg(160.0), 0.001)
    }

    @Test
    fun `ft in to cm conversion is correct`() {
        // 5 ft 11 in = 71 in = 180.34 cm
        assertEquals(180.34, ftInToCm(5, 11), 0.01)
    }

    @Test
    fun `lb per week to kg per week conversion is correct`() {
        assertEquals(0.22679, lbPerWeekToKgPerWeek(0.5), 0.0001)
    }

    @Test
    fun `save persists correct kg and cm values in entity`() = runTest {
        fillRequiredFields()
        // 5 ft 10 in = 70 in = 177.8 cm; 165 lb = 74.844 kg
        vm.setHeightFt("5")
        vm.setHeightIn("10")
        vm.setCurrentWeightLb("165")
        vm.setGoal(GoalSelection.MAINTAIN)

        vm.save()
        vm.saved.filter { it }.first() // wait for async save to complete

        val saved = db.userProfileDao().get(userId)
        assertNotNull(saved)
        assertEquals(177.8, saved!!.heightCm, 0.01)
        // goalRateKgPerWeek = 0 for MAINTAIN
        assertEquals(0.0, saved.goalRateKgPerWeek, 0.0001)
    }

    @Test
    fun `save converts CUT rate correctly — negative goalRateKgPerWeek`() = runTest {
        fillRequiredFields()
        vm.setGoal(GoalSelection.CUT)
        vm.setGoalRateLbPerWeek("1.0") // 1 lb/week = 0.45359 kg/week → stored as -0.45359

        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)
        assertNotNull(saved)
        assertEquals(-lbPerWeekToKgPerWeek(1.0), saved!!.goalRateKgPerWeek, 0.0001)
    }

    @Test
    fun `save converts BULK rate correctly — positive goalRateKgPerWeek`() = runTest {
        fillRequiredFields()
        vm.setGoal(GoalSelection.BULK)
        vm.setGoalRateLbPerWeek("0.5")

        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)
        assertNotNull(saved)
        assertEquals(lbPerWeekToKgPerWeek(0.5), saved!!.goalRateKgPerWeek, 0.0001)
    }

    @Test
    fun `save stores goalWeightKg when goalWeightLb is provided`() = runTest {
        fillRequiredFields()
        vm.setGoalWeightLb("150") // 150 lb → 68.039 kg

        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)
        assertNotNull(saved)
        assertNotNull(saved!!.goalWeightKg)
        assertEquals(lbToKg(150.0), saved.goalWeightKg!!, 0.001)
    }

    @Test
    fun `save stores null goalWeightKg when goalWeightLb is blank`() = runTest {
        fillRequiredFields()
        // goalWeightLb default is ""

        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)
        assertNotNull(saved)
        assertEquals(null, saved!!.goalWeightKg)
    }

    // -----------------------------------------------------------------------
    // 3. Persistence — seed weight
    // -----------------------------------------------------------------------

    @Test
    fun `save seeds a weight entry equal to currentWeightKg`() = runTest {
        fillRequiredFields()
        vm.setCurrentWeightLb("176") // 176 lb

        vm.save()
        vm.saved.filter { it }.first()

        val weights = db.weightEntryDao().getAll(userId)
        assertEquals(1, weights.size)
        assertEquals(lbToKg(176.0), weights[0].weightKg, 0.001)
    }

    @Test
    fun `save emits true on saved StateFlow`() = runTest {
        fillRequiredFields()
        vm.save()
        val isSaved = vm.saved.filter { it }.first()
        assertTrue(isSaved)
    }

    @Test
    fun `observeProfile returns the saved profile after save`() = runTest {
        fillRequiredFields()
        vm.save()
        vm.saved.filter { it }.first()

        val profile = repo.observeProfile().first()
        assertNotNull(profile)
        assertEquals(userId, profile!!.userId)
        assertEquals(Sex.MALE, profile.sex)
    }

    @Test
    fun `save applies macro defaults when overrides are blank`() = runTest {
        fillRequiredFields()
        // Leave proteinGPerKg and fatPct blank (defaults should be used)

        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)
        assertNotNull(saved)
        assertEquals(2.0, saved!!.proteinGPerKg, 0.0001)
        assertEquals(0.25, saved.fatPctOfCalories, 0.0001)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Fills all required form fields using a standard valid profile.
     * Pass [false] for any parameter to skip that field (to test missing-field validation).
     */
    private fun fillRequiredFields(
        sex: Boolean = true,
        birthYear: Boolean = true,
        heightFt: Boolean = true,
        heightIn: Boolean = true,
        currentWeightLb: Boolean = true,
        activityLevel: Boolean = true,
    ) {
        if (sex) vm.setSex(Sex.MALE)
        if (birthYear) vm.setBirthYear("1990")
        if (heightFt) vm.setHeightFt("5")
        if (heightIn) vm.setHeightIn("10")
        if (currentWeightLb) vm.setCurrentWeightLb("165")
        if (activityLevel) vm.setActivityLevel(ActivityLevel.MODERATE)
    }
}
