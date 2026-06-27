package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * Tests for saved-meal library operations in [TdeeRepository]:
 *   - [TdeeRepository.saveMeal] + [TdeeRepository.observeSavedMeals] round-trip
 *   - JSON round-trip for items with null grams
 *   - [TdeeRepository.saveMealFromGroup] snapshots a group
 *   - [TdeeRepository.logSavedMeal] inserts a new group matching saved items
 *   - [TdeeRepository.repeatMeal] creates a distinct new group with same item values
 *   - [TdeeRepository.repeatEntry] re-inserts a standalone copy
 *   - [TdeeRepository.foodEntriesForDate] returns the right day's entries
 *
 * Uses an in-memory Room v4 database, a fake [CurrentUser], and a fixed [Clock].
 * Fixed "now" = 2026-06-21T12:00:00Z → log-day 2026-06-21 (dayStartHour=0, UTC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositorySavedMealTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "saved-meal-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    @Before
    fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.userProfileDao().upsert(makeProfile(userId))

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
    }

    // -----------------------------------------------------------------------
    // saveMeal + observeSavedMeals round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `saveMeal and observeSavedMeals round-trip preserves all item fields`() = runTest {
        val id = repo.saveMeal(
            name = "Breakfast",
            items = listOf(
                NewFoodItem("Oats", 300.0, 10.0, 5.0, 55.0, 80.0),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            ),
        )

        val meals = repo.observeSavedMeals().first()
        assertEquals(1, meals.size)
        val meal = meals[0]
        assertEquals(id, meal.id)
        assertEquals("Breakfast", meal.name)
        assertEquals(userId, meal.userId)
        assertEquals(2, meal.items.size)

        val oats = meal.items[0]
        assertEquals("Oats", oats.name)
        assertEquals(300.0, oats.kcal, 0.001)
        assertEquals(10.0, oats.proteinG, 0.001)
        assertEquals(5.0, oats.fatG, 0.001)
        assertEquals(55.0, oats.carbG, 0.001)
        assertEquals(80.0, oats.grams!!, 0.001)

        val banana = meal.items[1]
        assertEquals("Banana", banana.name)
        assertNull("null grams should survive JSON round-trip", banana.grams)
    }

    @Test
    fun `saveMeal sets createdAt to clock instant`() = runTest {
        repo.saveMeal("Lunch", listOf(NewFoodItem("Chicken", 250.0, 30.0, 5.0, 0.0, null)))

        val meals = repo.observeSavedMeals().first()
        assertEquals(fixedNow, meals[0].createdAt)
    }

    @Test
    fun `observeSavedMeals returns meals newest first`() = runTest {
        // Two meals inserted at same fixed clock — second insert gets higher auto-id.
        repo.saveMeal("First", listOf(NewFoodItem("A", 100.0, 0.0, 0.0, 0.0, null)))
        repo.saveMeal("Second", listOf(NewFoodItem("B", 200.0, 0.0, 0.0, 0.0, null)))

        val meals = repo.observeSavedMeals().first()
        assertEquals(2, meals.size)
        // DESC by createdAt; equal timestamps → higher id first.
        assertEquals("Second", meals[0].name)
        assertEquals("First", meals[1].name)
    }

    @Test
    fun `deleteSavedMeal removes the meal`() = runTest {
        val id = repo.saveMeal("ToDelete", listOf(NewFoodItem("X", 100.0, 0.0, 0.0, 0.0, null)))
        repo.deleteSavedMeal(id)

        val meals = repo.observeSavedMeals().first()
        assertTrue(meals.isEmpty())
    }

    // -----------------------------------------------------------------------
    // saveMealFromGroup
    // -----------------------------------------------------------------------

    @Test
    fun `saveMealFromGroup snapshots the group's items`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Rice", 200.0, 4.0, 1.0, 44.0, 150.0),
                NewFoodItem("Chicken", 250.0, 30.0, 5.0, 0.0, null),
            ),
        )

        repo.saveMealFromGroup("Rice and Chicken", mealId)

        val meals = repo.observeSavedMeals().first()
        assertEquals(1, meals.size)
        assertEquals("Rice and Chicken", meals[0].name)
        assertEquals(2, meals[0].items.size)
        assertEquals("Rice", meals[0].items[0].name)
        assertEquals("Chicken", meals[0].items[1].name)
    }

    // -----------------------------------------------------------------------
    // logSavedMeal
    // -----------------------------------------------------------------------

    @Test
    fun `logSavedMeal inserts a new food group matching saved meal items`() = runTest {
        val savedId = repo.saveMeal(
            "Dinner",
            listOf(
                NewFoodItem("Pasta", 350.0, 12.0, 3.0, 68.0, 200.0),
            ),
        )

        val newMealId = repo.logSavedMeal(savedId)

        assertNotNull(newMealId)
        assertTrue("Returned mealId should be non-blank", newMealId.isNotBlank())

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(1, entries.size)
        assertEquals("Pasta", entries[0].name)
        assertEquals(350.0, entries[0].kcal, 0.001)
        assertEquals(newMealId, entries[0].mealId)
    }

    @Test
    fun `logSavedMeal returns empty string for unknown savedMealId`() = runTest {
        val result = repo.logSavedMeal(9999L)
        assertEquals("", result)
        assertTrue(db.foodEntryDao().getActive(userId).isEmpty())
    }

    // -----------------------------------------------------------------------
    // repeatMeal
    // -----------------------------------------------------------------------

    @Test
    fun `repeatMeal creates a distinct new group with same item values`() = runTest {
        val originalMealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Oats", 300.0, 10.0, 5.0, 55.0, null),
                NewFoodItem("Egg", 70.0, 6.0, 5.0, 0.0, null),
            ),
        )

        val newMealId = repo.repeatMeal(originalMealId)

        assertNotEquals("New mealId should differ from original", originalMealId, newMealId)

        val allActive = db.foodEntryDao().getActive(userId)
        assertEquals("4 entries total (2 original + 2 repeated)", 4, allActive.size)

        val repeated = allActive.filter { it.mealId == newMealId }
        assertEquals(2, repeated.size)
        val names = repeated.map { it.name }.toSet()
        assertTrue(names.contains("Oats"))
        assertTrue(names.contains("Egg"))
        assertEquals(300.0, repeated.first { it.name == "Oats" }.kcal, 0.001)
    }

    // -----------------------------------------------------------------------
    // repeatEntry
    // -----------------------------------------------------------------------

    @Test
    fun `repeatEntry inserts a standalone copy with null mealId`() = runTest {
        repo.addFood(name = "Apple", kcal = 95.0, proteinG = 0.5, fatG = 0.3, carbG = 25.0)
        val original = db.foodEntryDao().getActive(userId).first()

        repo.repeatEntry(original.id)

        val allActive = db.foodEntryDao().getActive(userId)
        assertEquals(2, allActive.size)
        val copy = allActive.first { it.id != original.id }
        assertEquals("Apple", copy.name)
        assertEquals(95.0, copy.kcal, 0.001)
        assertNull("Repeated standalone entry must have null mealId", copy.mealId)
    }

    // -----------------------------------------------------------------------
    // foodEntriesForDate
    // -----------------------------------------------------------------------

    @Test
    fun `foodEntriesForDate returns entries only on the requested day`() = runTest {
        // today: 2026-06-21 (fixedNow = 2026-06-21T12:00:00Z)
        val today = LocalDate.of(2026, 6, 21)
        val yesterday = today.minusDays(1)

        repo.addFood(
            name = "TodayFood", kcal = 300.0, proteinG = 0.0, fatG = 0.0, carbG = 0.0,
            loggedDate = today,
        )
        repo.addFood(
            name = "YesterdayFood", kcal = 200.0, proteinG = 0.0, fatG = 0.0, carbG = 0.0,
            loggedDate = yesterday,
        )

        val todayEntries = repo.foodEntriesForDate(today)
        val yesterdayEntries = repo.foodEntriesForDate(yesterday)

        assertEquals(1, todayEntries.size)
        assertEquals("TodayFood", todayEntries[0].name)

        assertEquals(1, yesterdayEntries.size)
        assertEquals("YesterdayFood", yesterdayEntries[0].name)
    }

    @Test
    fun `foodEntriesForDate excludes soft-deleted entries`() = runTest {
        val today = LocalDate.of(2026, 6, 21)
        repo.addFood(name = "Kept", kcal = 100.0, proteinG = 0.0, fatG = 0.0, carbG = 0.0,
            loggedDate = today)
        repo.addFood(name = "Deleted", kcal = 200.0, proteinG = 0.0, fatG = 0.0, carbG = 0.0,
            loggedDate = today)

        val deletedEntry = db.foodEntryDao().getActive(userId).first { it.name == "Deleted" }
        repo.softDeleteFood(deletedEntry.id)

        val entries = repo.foodEntriesForDate(today)
        assertEquals(1, entries.size)
        assertEquals("Kept", entries[0].name)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeProfile(uid: String) = UserProfileEntity(
        userId = uid,
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
