package com.tdee.app.addfood

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
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
 * Unit tests for [AddFoodViewModel]: validation (canSave) and persistence.
 *
 * Uses Robolectric + in-memory Room. A fixed clock and fake [CurrentUser] keep results
 * deterministic. The fixed clock noon on 2026-06-21 UTC matches the pattern established
 * in TdeeRepositoryTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddFoodViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository
    private lateinit var vm: AddFoodViewModel

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "add-food-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        // A profile and seed weight are required so the repo resolves dayStartHour correctly.
        val now = fixedNow
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
                createdAt = now,
                updatedAt = now,
            )
        )
        db.weightEntryDao().insert(
            WeightEntryEntity(
                userId = userId,
                timestamp = now,
                weightKg = 80.0,
                source = WeightSource.MANUAL,
                createdAt = now,
            )
        )

        repo = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            currentUser = fakeCurrentUser,
            zone = zone,
            clock = fixedClock,
        )

        vm = AddFoodViewModel(repo)
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
    fun `canSave is false when name is blank but kcal is valid`() {
        vm.setKcal("500")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when name is provided but kcal is blank`() {
        vm.setName("Apple")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when kcal is non-numeric`() {
        vm.setName("Apple")
        vm.setKcal("abc")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is false when kcal is negative`() {
        vm.setName("Apple")
        vm.setKcal("-10")
        assertFalse(vm.form.value.canSave)
    }

    @Test
    fun `canSave is true when name and kcal are valid`() {
        vm.setName("Apple")
        vm.setKcal("95")
        assertTrue(vm.form.value.canSave)
    }

    @Test
    fun `canSave is true when kcal is zero`() {
        vm.setName("Water")
        vm.setKcal("0")
        assertTrue(vm.form.value.canSave)
    }

    @Test
    fun `canSave is true with only name and kcal — macros optional`() {
        vm.setName("Rice")
        vm.setKcal("200")
        // proteinG, fatG, carbG, grams all blank — should still be saveable
        assertTrue(vm.form.value.canSave)
    }

    // -----------------------------------------------------------------------
    // 2. Persistence — save() inserts entry for current user
    // -----------------------------------------------------------------------

    @Test
    fun `save persists entry that appears in todayFoodEntries`() = runTest {
        vm.setName("Banana")
        vm.setKcal("89")
        vm.setProteinG("1.1")
        vm.setFatG("0.3")
        vm.setCarbG("23")

        vm.save()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("Banana", entry.name)
        assertEquals(89.0, entry.kcal, 0.001)
        assertEquals(1.1, entry.proteinG, 0.001)
        assertEquals(0.3, entry.fatG, 0.001)
        assertEquals(23.0, entry.carbG, 0.001)
        assertEquals(userId, entry.userId)
        assertEquals(FoodSourceDb.MANUAL, entry.sourceDb)
    }

    @Test
    fun `save with blank macros defaults them to 0`() = runTest {
        vm.setName("Plain water")
        vm.setKcal("0")
        // All macro fields left blank

        vm.save()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(0.0, entry.proteinG, 0.0)
        assertEquals(0.0, entry.fatG, 0.0)
        assertEquals(0.0, entry.carbG, 0.0)
    }

    @Test
    fun `save emits true on saved StateFlow`() = runTest {
        vm.setName("Egg")
        vm.setKcal("78")

        vm.save()
        val isSaved = vm.saved.filter { it }.first()
        assertTrue(isSaved)
    }

    @Test
    fun `save does nothing when canSave is false`() = runTest {
        // name is blank — canSave == false
        vm.setKcal("500")
        vm.save()

        val entries = repo.todayFoodEntries()
        assertTrue(entries.isEmpty())
        assertFalse(vm.saved.value)
    }
}
