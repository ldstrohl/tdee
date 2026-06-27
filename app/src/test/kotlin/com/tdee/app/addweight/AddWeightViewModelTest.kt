package com.tdee.app.addweight

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.data.WeightEntryEntity
import com.tdee.app.data.WeightSource
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
 * Unit tests for [AddWeightViewModel]: validation (canSave) and persistence with lb→kg conversion.
 *
 * Uses Robolectric + in-memory Room. A fixed clock and fake [CurrentUser] keep results
 * deterministic. Pattern matches [com.tdee.app.addfood.AddFoodViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddWeightViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository
    private lateinit var vm: AddWeightViewModel

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "add-weight-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        // A profile and seed weight are required so the repo resolves the user correctly.
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
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )
        )
        db.weightEntryDao().insert(
            WeightEntryEntity(
                userId = userId,
                timestamp = fixedNow,
                weightKg = 80.0,
                source = WeightSource.MANUAL,
                createdAt = fixedNow,
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

        vm = AddWeightViewModel(repo)
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
    fun `canSave is false on blank input`() {
        assertFalse(vm.canSave)
    }

    @Test
    fun `canSave is false for non-numeric input`() {
        vm.setWeightLb("abc")
        assertFalse(vm.canSave)
    }

    @Test
    fun `canSave is false for zero`() {
        vm.setWeightLb("0")
        assertFalse(vm.canSave)
    }

    @Test
    fun `canSave is false for negative`() {
        vm.setWeightLb("-5")
        assertFalse(vm.canSave)
    }

    @Test
    fun `canSave is true for valid positive number`() {
        vm.setWeightLb("185.5")
        assertTrue(vm.canSave)
    }

    @Test
    fun `canSave is true for integer string`() {
        vm.setWeightLb("200")
        assertTrue(vm.canSave)
    }

    // -----------------------------------------------------------------------
    // 2. Persistence — save() stores weight with correct lb→kg conversion
    // -----------------------------------------------------------------------

    @Test
    fun `save persists weight entry with correct lb to kg conversion`() = runTest {
        val weightLb = 185.0
        val expectedKg = weightLb * 0.45359237

        vm.setWeightLb(weightLb.toString())
        vm.save()
        vm.saved.filter { it }.first()

        // Read all weight entries directly from the DAO to verify persistence.
        val entries = db.weightEntryDao().getAll(userId)
        // The seed weight was inserted in setup, so there are 2 entries.
        val logged = entries.first { it.weightKg != 80.0 }
        assertEquals(
            "Stored kg should be weightLb × 0.45359237",
            expectedKg,
            logged.weightKg,
            0.0001,
        )
        assertEquals(userId, logged.userId)
        assertEquals(WeightSource.MANUAL, logged.source)
    }

    @Test
    fun `save emits true on saved StateFlow`() = runTest {
        vm.setWeightLb("175")
        vm.save()
        val isSaved = vm.saved.filter { it }.first()
        assertTrue(isSaved)
    }

    @Test
    fun `save does nothing when canSave is false`() = runTest {
        // blank input
        vm.save()
        assertFalse(vm.saved.value)
        // Only the seed weight entry exists.
        val entries = db.weightEntryDao().getAll(userId)
        assertEquals(1, entries.size)
    }
}
