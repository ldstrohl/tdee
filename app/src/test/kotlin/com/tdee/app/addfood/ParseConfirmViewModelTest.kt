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
            savedMealDao = db.savedMealDao(),
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

    // -----------------------------------------------------------------------
    // Meal totals
    // -----------------------------------------------------------------------

    @Test
    fun `totalKcal sums kcal of valid items only`() = runTest {
        vm.setText("a and b and c")
        vm.parse()
        assertEquals(3, vm.state.value.items.size)

        // Item 0: valid
        vm.setKcal(0, "100")
        // Item 1: valid
        vm.setKcal(1, "200")
        // Item 2: kcal blank → invalid → excluded from totals

        assertEquals(300.0, vm.state.value.totalKcal, 0.001)
    }

    @Test
    fun `totalProteinG sums protein of valid items using parsed value or 0`() = runTest {
        vm.setText("a and b")
        vm.parse()

        vm.setKcal(0, "100")
        vm.setProteinG(0, "20")
        vm.setKcal(1, "200")
        // item 1 proteinG left blank → 0

        assertEquals(20.0, vm.state.value.totalProteinG, 0.001)
    }

    @Test
    fun `totals are zero when no items are valid`() = runTest {
        vm.setText("apple")
        vm.parse()
        // kcal is blank → not valid
        assertEquals(0.0, vm.state.value.totalKcal, 0.001)
        assertEquals(0.0, vm.state.value.totalProteinG, 0.001)
        assertEquals(0.0, vm.state.value.totalFatG, 0.001)
        assertEquals(0.0, vm.state.value.totalCarbG, 0.001)
    }

    // -----------------------------------------------------------------------
    // Per-item multiplier (factor)
    // -----------------------------------------------------------------------

    @Test
    fun `setFactor scales totals live without losing the base value`() = runTest {
        vm.setText("apple")
        vm.parse()
        vm.setKcal(0, "100")
        vm.setProteinG(0, "10")

        vm.setFactor(0, "1.5")

        assertEquals(150.0, vm.state.value.totalKcal, 0.001)
        assertEquals(15.0, vm.state.value.totalProteinG, 0.001)
        // Base (unscaled) value is preserved so the factor is re-adjustable.
        assertEquals("100", vm.state.value.items[0].kcal)

        // Re-adjusting the factor recomputes from the same base value (not lossy).
        vm.setFactor(0, "0.5")
        assertEquals(50.0, vm.state.value.totalKcal, 0.001)
    }

    @Test
    fun `default factor of 1 does not change totals`() = runTest {
        vm.setText("apple")
        vm.parse()
        vm.setKcal(0, "100")

        assertEquals(100.0, vm.state.value.totalKcal, 0.001)
    }

    @Test
    fun `saveAll applies each item's factor to the persisted kcal and macros`() = runTest {
        vm.setText("apple and banana")
        vm.parse()
        vm.setKcal(0, "100")
        vm.setProteinG(0, "10")
        vm.setFactor(0, "1.5")
        vm.setKcal(1, "200")
        // banana keeps the default factor of 1 (no scaling)

        vm.saveAll()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        val apple = entries.first { it.name == "apple" }
        val banana = entries.first { it.name == "banana" }
        assertEquals(150.0, apple.kcal, 0.001)
        assertEquals(15.0, apple.proteinG, 0.001)
        assertEquals(200.0, banana.kcal, 0.001)
    }

    // -----------------------------------------------------------------------
    // saveAll uses addFoodGroup (shared mealId)
    // -----------------------------------------------------------------------

    @Test
    fun `saveAll writes all valid items sharing a single mealId`() = runTest {
        vm.setText("apple and banana")
        vm.parse()

        vm.setKcal(0, "95")
        vm.setKcal(1, "105")

        vm.saveAll()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(2, entries.size)
        val mealIds = entries.map { it.mealId }.distinct()
        assertEquals("All entries should share one mealId", 1, mealIds.size)
        assertNotNull("mealId should not be null", mealIds[0])
    }

    @Test
    fun `saveAll shares mealId only across valid items (invalid items excluded)`() = runTest {
        vm.setText("a, b, c")
        vm.parse()

        // Item 0: valid
        vm.setKcal(0, "100")
        // Item 1: invalid (blank kcal)
        // Item 2: valid
        vm.setKcal(2, "200")

        vm.saveAll()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(2, entries.size)
        val mealIds = entries.map { it.mealId }.distinct()
        assertEquals(1, mealIds.size)
        assertNotNull(mealIds[0])
    }

    // -----------------------------------------------------------------------
    // saveAll(mealName) stamps mealName on the group (N3 / N5)
    // -----------------------------------------------------------------------

    @Test
    fun `saveAll with mealName stamps that name on all group entries`() = runTest {
        vm.setText("apple and banana")
        vm.parse()
        vm.setKcal(0, "95")
        vm.setKcal(1, "105")

        vm.saveAll("Lunch")
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.mealName == "Lunch" })
    }

    @Test
    fun `saveAll with blank mealName stores null mealName`() = runTest {
        vm.setText("apple")
        vm.parse()
        vm.setKcal(0, "95")

        vm.saveAll("   ")
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(1, entries.size)
        assertNull(entries[0].mealName)
    }

    // -----------------------------------------------------------------------
    // saveMealAndAdd saves to library AND adds group (N5)
    // -----------------------------------------------------------------------

    @Test
    fun `saveMealAndAdd saves to library and adds group with that mealName`() = runTest {
        vm.setText("oats and egg")
        vm.parse()
        vm.setKcal(0, "300")
        vm.setKcal(1, "70")

        vm.saveMealAndAdd("Breakfast")
        vm.saved.filter { it }.first()

        // Group was added to today's log
        val entries = repo.todayFoodEntries()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.mealName == "Breakfast" })

        // Also saved to the library
        val meals = repo.observeSavedMeals().first()
        assertEquals(1, meals.size)
        assertEquals("Breakfast", meals[0].name)
    }

    // -----------------------------------------------------------------------
    // Parse failures surface as a dismissible error (and clear on a later success)
    // -----------------------------------------------------------------------

    @Test
    fun `parse failure sets parseError and clears items`() = runTest {
        val failing = object : FoodParser {
            override suspend fun parse(text: String): ParseResult =
                ParseResult.Failure(ParseErrorKind.NO_KEY, "Add an API key in Settings.")
        }
        val failVm = ParseConfirmViewModel(failing, repo)

        failVm.setText("2 eggs")
        failVm.parse()

        assertEquals("Add an API key in Settings.", failVm.state.value.parseError)
        assertTrue(failVm.state.value.items.isEmpty())
        assertFalse(failVm.state.value.parsing)
    }

    @Test
    fun `a successful parse clears a prior parseError`() = runTest {
        vm.setText("2 eggs and oatmeal")
        vm.parse()

        assertNull(vm.state.value.parseError)
        assertEquals(2, vm.state.value.items.size)
    }
}
