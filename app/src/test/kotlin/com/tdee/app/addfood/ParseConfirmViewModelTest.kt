package com.tdee.app.addfood

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
 * Unit tests for [ParseConfirmViewModel]: parse populates the editable list, edits + saveAll
 * persist the expected entries, and invalid items (blank name / invalid kcal) are skipped on save.
 *
 * Robolectric + in-memory Room, fixed clock (noon 2026-06-21 UTC) and fake [CurrentUser], matching
 * the harness in AddFoodViewModelTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParseConfirmViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository
    private lateinit var vm: ParseConfirmViewModel

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "parse-confirm-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

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
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            currentUser = fakeCurrentUser,
            zone = zone,
            clock = fixedClock,
        )

        vm = ParseConfirmViewModel(LocalHeuristicFoodParser(), repo)
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `parse populates the editable item list`() = runTest {
        vm.setText("2 eggs and oatmeal")
        vm.parse()

        val items = vm.state.value.items
        assertEquals(2, items.size)
        assertEquals("2 eggs", items[0].name)
        assertEquals("oatmeal", items[1].name)
        // Placeholder parser yields blank numbers.
        assertEquals("", items[0].kcal)
    }

    @Test
    fun `editing fields and saveAll writes the entries with edited values`() = runTest {
        vm.setText("2 eggs and oatmeal")
        vm.parse()

        vm.setKcal(0, "150")
        vm.setProteinG(0, "12")
        vm.setKcal(1, "200")
        vm.setCarbG(1, "40")

        vm.saveAll()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries().sortedBy { it.name }
        assertEquals(2, entries.size)

        val eggs = entries.first { it.name == "2 eggs" }
        assertEquals(150.0, eggs.kcal, 0.001)
        assertEquals(12.0, eggs.proteinG, 0.001)

        val oats = entries.first { it.name == "oatmeal" }
        assertEquals(200.0, oats.kcal, 0.001)
        assertEquals(40.0, oats.carbG, 0.001)
    }

    @Test
    fun `invalid items are skipped on save`() = runTest {
        vm.setText("a, b, c")
        vm.parse()
        assertEquals(3, vm.state.value.items.size)

        // Item 0: valid (name from parse + kcal).
        vm.setKcal(0, "100")
        // Item 1: blank name, kcal set → invalid.
        vm.setName(1, "")
        vm.setKcal(1, "100")
        // Item 2: name present but kcal blank → invalid (left as parsed: blank kcal).

        vm.saveAll()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(1, entries.size)
        assertEquals("a", entries[0].name)
        assertEquals(100.0, entries[0].kcal, 0.001)
    }

    @Test
    fun `canSave is false until at least one item is valid`() = runTest {
        vm.setText("apple")
        vm.parse()
        // Parsed item has blank kcal → not yet valid.
        assertFalse(vm.state.value.canSave)

        vm.setKcal(0, "95")
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `addItem and removeItem mutate the list`() = runTest {
        vm.setText("apple")
        vm.parse()
        assertEquals(1, vm.state.value.items.size)

        vm.addItem()
        assertEquals(2, vm.state.value.items.size)

        vm.removeItem(0)
        assertEquals(1, vm.state.value.items.size)
    }
}
