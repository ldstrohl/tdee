package com.tdee.app.editprofile

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.data.WeightEntryDao
import com.tdee.app.onboarding.GoalSelection
import com.tdee.app.onboarding.lbPerWeekToKgPerWeek
import com.tdee.app.onboarding.lbToKg
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Unit tests for [EditProfileViewModel] and [TdeeRepository.updateProfile].
 *
 * Uses Robolectric + in-memory Room. A fixed clock and fake [CurrentUser] keep results
 * deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditProfileViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository
    private lateinit var weightEntryDao: WeightEntryDao

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "edit-profile-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    /** A baseline profile already stored in the DB (simulating post-onboarding state). */
    private val baseProfile = UserProfileEntity(
        userId = userId,
        sex = Sex.MALE,
        birthYear = 1990,
        heightCm = 177.8, // 5 ft 10 in
        activityLevel = ActivityLevel.MODERATE,
        goalRateKgPerWeek = -0.45359237, // ~1 lb/week CUT
        goalWeightKg = lbToKg(175.0),
        proteinGPerKg = 2.0,
        fatPctOfCalories = 0.25,
        dayStartHour = 0,
        smoothingWindowDays = 14,
        tdeeWindowDays = 14,
        createdAt = Instant.parse("2026-01-01T08:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T08:00:00Z"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        weightEntryDao = db.weightEntryDao()

        repo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = weightEntryDao,
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
    // 1. updateProfile — no weight entry side effect
    // -----------------------------------------------------------------------

    @Test
    fun `updateProfile updates goal fields without inserting a weight entry`() = runTest {
        // Pre-seed the base profile (no weight entries at this point).
        db.userProfileDao().upsert(baseProfile)
        val weightCountBefore = weightEntryDao.getAll(userId).size
        assertEquals("No weight entries expected before updateProfile", 0, weightCountBefore)

        val updatedProfile = baseProfile.copy(
            goalWeightKg = lbToKg(160.0),
            goalRateKgPerWeek = -lbPerWeekToKgPerWeek(0.5),
        )
        repo.updateProfile(updatedProfile)

        val saved = db.userProfileDao().get(userId)
        assertNotNull(saved)
        assertEquals(lbToKg(160.0), saved!!.goalWeightKg!!, 0.001)
        assertEquals(-lbPerWeekToKgPerWeek(0.5), saved.goalRateKgPerWeek, 0.0001)

        val weightCountAfter = weightEntryDao.getAll(userId).size
        assertEquals("updateProfile must not insert any weight entry", 0, weightCountAfter)
    }

    @Test
    fun `updateProfile preserves createdAt and sets updatedAt to clock instant`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        repo.updateProfile(baseProfile.copy(birthYear = 1985))

        val saved = db.userProfileDao().get(userId)!!
        assertEquals("createdAt must be preserved", baseProfile.createdAt, saved.createdAt)
        assertEquals("updatedAt must equal clock instant", fixedNow, saved.updatedAt)
        assertEquals(1985, saved.birthYear)
    }

    // -----------------------------------------------------------------------
    // 2. EditProfileViewModel — prefill from existing profile
    // -----------------------------------------------------------------------

    @Test
    fun `EditProfileViewModel prefills sex and birthYear from stored profile`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        // Wait for loading to finish (loading = false)
        vm.form.filter { !it.loading }.first()

        val form = vm.form.value
        assertEquals(Sex.MALE, form.sex)
        assertEquals("1990", form.birthYear)
    }

    @Test
    fun `EditProfileViewModel prefills height from stored profile in ft and in`() = runTest {
        db.userProfileDao().upsert(baseProfile) // heightCm = 177.8 → 5 ft 10 in

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        val form = vm.form.value
        assertEquals("5", form.heightFt)
        assertEquals("10", form.heightIn)
    }

    @Test
    fun `EditProfileViewModel prefills CUT goal and rate from stored profile`() = runTest {
        db.userProfileDao().upsert(baseProfile) // goalRateKgPerWeek = -0.45359237 → CUT ~1 lb/wk

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        val form = vm.form.value
        assertEquals(GoalSelection.CUT, form.goal)
        // Rate should be ~1.0 lb/week (parsed from string)
        val rate = form.goalRateLbPerWeek.toDoubleOrNull()
        assertNotNull("goalRateLbPerWeek should be parseable", rate)
        assertEquals(1.0, rate!!, 0.01)
    }

    @Test
    fun `EditProfileViewModel prefills goalWeightLb from stored profile`() = runTest {
        db.userProfileDao().upsert(baseProfile) // goalWeightKg = lbToKg(175.0)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        val form = vm.form.value
        val goalLb = form.goalWeightLb.toDoubleOrNull()
        assertNotNull("goalWeightLb should be parseable", goalLb)
        assertEquals(175.0, goalLb!!, 0.5) // half-lb tolerance for rounding
    }

    @Test
    fun `EditProfileViewModel prefills activityLevel from stored profile`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        assertEquals(ActivityLevel.MODERATE, vm.form.value.activityLevel)
    }

    // -----------------------------------------------------------------------
    // 3. EditProfileViewModel — save persists converted edits
    // -----------------------------------------------------------------------

    @Test
    fun `save persists goal weight 175 lb as approximately 79_4 kg`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        vm.setGoalWeightLb("175")
        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)!!
        assertNotNull(saved.goalWeightKg)
        assertEquals(lbToKg(175.0), saved.goalWeightKg!!, 0.01)
    }

    @Test
    fun `save persists CUT 1 lb per week as goalRateKgPerWeek approximately minus 0_4536`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        vm.setGoal(GoalSelection.CUT)
        vm.setGoalRateLbPerWeek("1.0")
        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)!!
        assertEquals(-lbPerWeekToKgPerWeek(1.0), saved.goalRateKgPerWeek, 0.0001)
    }

    @Test
    fun `save does not insert any weight entry`() = runTest {
        db.userProfileDao().upsert(baseProfile)
        val weightCountBefore = weightEntryDao.getAll(userId).size

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        vm.save()
        vm.saved.filter { it }.first()

        val weightCountAfter = weightEntryDao.getAll(userId).size
        assertEquals("save() must not create weight entries", weightCountBefore, weightCountAfter)
    }

    @Test
    fun `save clears goalWeightKg when goalWeightLb is blank`() = runTest {
        db.userProfileDao().upsert(baseProfile) // starts with goalWeightKg set

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        vm.setGoalWeightLb("") // clear it
        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)!!
        assertNull("goalWeightKg should be null when goalWeightLb is blank", saved.goalWeightKg)
    }

    @Test
    fun `save emits true on saved StateFlow`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        vm.save()
        val isSaved = vm.saved.filter { it }.first()
        assertEquals(true, isSaved)
    }

    // -----------------------------------------------------------------------
    // 4. Fat % — percent input / fraction storage / prefill
    // -----------------------------------------------------------------------

    @Test
    fun `prefill shows blank fatPct when stored fraction is default 0_25`() = runTest {
        db.userProfileDao().upsert(baseProfile) // fatPctOfCalories = 0.25

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        assertEquals("", vm.form.value.fatPct)
    }

    @Test
    fun `prefill converts stored fraction 0_30 to percent string 30`() = runTest {
        db.userProfileDao().upsert(baseProfile.copy(fatPctOfCalories = 0.30))

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        assertEquals("30", vm.form.value.fatPct)
    }

    @Test
    fun `save stores fat percent 35 as fraction 0_35`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        vm.setFatPct("35")
        vm.save()
        vm.saved.filter { it }.first()

        val saved = db.userProfileDao().get(userId)!!
        assertEquals(0.35, saved.fatPctOfCalories, 0.0001)
    }

    @Test
    fun `fatPct out of range 150 makes canSave false`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        vm.setFatPct("150")
        assertEquals(false, vm.form.value.canSave)
    }

    // -----------------------------------------------------------------------
    // 5. Missing required fields
    // -----------------------------------------------------------------------

    @Test
    fun `missingRequiredFields lists Height when height is cleared`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        vm.setHeightFt("")
        val missing = vm.form.value.missingRequiredFields
        assertEquals(true, "Height" in missing)
        assertEquals(false, vm.form.value.canSave)
    }

    @Test
    fun `missingRequiredFields is empty for a fully prefilled profile`() = runTest {
        db.userProfileDao().upsert(baseProfile)

        val vm = EditProfileViewModel(repo)
        vm.form.filter { !it.loading }.first()

        assertEquals(emptyList<String>(), vm.form.value.missingRequiredFields)
        assertEquals(true, vm.form.value.canSave)
    }
}
