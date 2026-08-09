package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Tests for [TdeeRepository.searchMeals]: matching (name / mealName / item name), dedup of
 * repeated logged meals/entries, cross-source (saved vs. logged) suppression, ranking, literal
 * (non-wildcard) query matching, soft-delete exclusion, user scoping, and blank-query browse mode.
 *
 * Uses an in-memory Room v4 database, a fake [CurrentUser], and a fixed [Clock].
 * Fixed "now" = 2026-06-21T12:00:00Z → log-day 2026-06-21 (dayStartHour=0, UTC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositorySearchTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val today = LocalDate.of(2026, 6, 21)

    private val userId = "search-test-user"
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
    // 1. Group matched by mealName, case-insensitive
    // -----------------------------------------------------------------------

    @Test
    fun `group matched by mealName is case-insensitive`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Chicken", 300.0, 30.0, 10.0, 0.0, null),
                NewFoodItem("Broccoli", 50.0, 4.0, 0.0, 10.0, null),
            ),
            mealName = "Chicken Bowl",
        )

        val results = repo.searchMeals("BOWL")

        assertEquals(1, results.size)
        val result = results[0] as MealSearchResult.LoggedMeal
        assertEquals(mealId, result.mealId)
        assertEquals("Chicken Bowl", result.title)
        assertEquals(2, result.items.size)
    }

    // -----------------------------------------------------------------------
    // 2. Group matched by ITEM name only → whole hydrated group returned
    // -----------------------------------------------------------------------

    @Test
    fun `group matched by item name only returns the whole hydrated group`() = runTest {
        repo.addFoodGroup(
            listOf(
                NewFoodItem("Salmon", 350.0, 34.0, 20.0, 0.0, null),
                NewFoodItem("Rice", 200.0, 4.0, 1.0, 44.0, null),
            ),
            mealName = "Dinner",
        )

        val results = repo.searchMeals("salmon")

        assertEquals(1, results.size)
        val result = results[0] as MealSearchResult.LoggedMeal
        assertEquals("Dinner", result.title)
        assertEquals(2, result.items.size)
        val names = result.items.map { it.name }.toSet()
        assertTrue(names.contains("Salmon"))
        assertTrue(names.contains("Rice"))
    }

    // -----------------------------------------------------------------------
    // 3. Same-named meal logged 3x → exactly one LoggedMeal, most recent representative
    // -----------------------------------------------------------------------

    @Test
    fun `same-named meal logged three times collapses to one result with most recent lastLogged`() = runTest {
        repo.addFoodGroup(
            listOf(NewFoodItem("Whey", 120.0, 24.0, 1.0, 2.0, null)),
            loggedDate = today.minusDays(10),
            mealName = "Protein Shake",
        )
        repo.addFoodGroup(
            listOf(NewFoodItem("Whey", 120.0, 24.0, 1.0, 2.0, null)),
            loggedDate = today.minusDays(5),
            mealName = "Protein Shake",
        )
        val latestMealId = repo.addFoodGroup(
            listOf(NewFoodItem("Whey", 120.0, 24.0, 1.0, 2.0, null)),
            loggedDate = today,
            mealName = "Protein Shake",
        )

        val results = repo.searchMeals("shake")

        assertEquals(1, results.size)
        val result = results[0] as MealSearchResult.LoggedMeal
        assertEquals(latestMealId, result.mealId)
        assertEquals(fixedNow, result.lastLogged)
    }

    // -----------------------------------------------------------------------
    // 4. Standalone entries with the same name → one LoggedEntry (most recent)
    // -----------------------------------------------------------------------

    @Test
    fun `standalone entries with the same name collapse to one most-recent LoggedEntry`() = runTest {
        repo.addFood(name = "Apple", kcal = 95.0, proteinG = 0.5, fatG = 0.3, carbG = 25.0,
            loggedDate = today.minusDays(10))
        repo.addFood(name = "Apple", kcal = 95.0, proteinG = 0.5, fatG = 0.3, carbG = 25.0,
            loggedDate = today.minusDays(3))
        repo.addFood(name = "Apple", kcal = 95.0, proteinG = 0.5, fatG = 0.3, carbG = 25.0,
            loggedDate = today)

        val results = repo.searchMeals("apple")

        assertEquals(1, results.size)
        val result = results[0] as MealSearchResult.LoggedEntry
        assertEquals(fixedNow, result.lastLogged)
        assertEquals(1, result.items.size)
    }

    // -----------------------------------------------------------------------
    // 5. Saved meal matched by name AND (separately) by item name
    // -----------------------------------------------------------------------

    @Test
    fun `saved meals match by title or by item name`() = runTest {
        repo.saveMeal(
            "Green Smoothie",
            listOf(
                NewFoodItem("Spinach", 20.0, 2.0, 0.0, 3.0, null),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            ),
        )
        repo.saveMeal(
            "Post Workout",
            listOf(NewFoodItem("Green Tea", 0.0, 0.0, 0.0, 0.0, null)),
        )

        val results = repo.searchMeals("green")

        assertEquals(2, results.size)
        val titles = results.map { it.title }.toSet()
        assertTrue(titles.contains("Green Smoothie"))
        assertTrue(titles.contains("Post Workout"))
    }

    // -----------------------------------------------------------------------
    // 6. Query matching only JSON syntax of the saved items column doesn't false-positive
    // -----------------------------------------------------------------------

    @Test
    fun `query matching only the saved-items JSON syntax does not match unrelated saved meals`() = runTest {
        repo.saveMeal("Chicken", listOf(NewFoodItem("Chicken Breast", 165.0, 31.0, 3.6, 0.0, null)))

        val results = repo.searchMeals("kcal")

        assertTrue(results.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 7. Ranking: prefix > substring > item-only; saved before logged at equal rank
    // -----------------------------------------------------------------------

    @Test
    fun `ranking orders by title match quality then saved-before-logged then recency`() = runTest {
        repo.saveMeal("Chicken Bowl", listOf(NewFoodItem("Chicken", 300.0, 30.0, 10.0, 0.0, null)))
        repo.saveMeal("Grilled Chicken", listOf(NewFoodItem("Chicken Thigh", 250.0, 25.0, 15.0, 0.0, null)))
        repo.saveMeal("Salad", listOf(NewFoodItem("Chicken Breast", 165.0, 31.0, 3.6, 0.0, null)))
        repo.addFoodGroup(
            listOf(NewFoodItem("Chicken Wrap", 400.0, 25.0, 12.0, 40.0, null)),
            mealName = "Chicken Wrap",
        )

        val results = repo.searchMeals("chicken")

        assertEquals(
            listOf("Chicken Bowl", "Chicken Wrap", "Grilled Chicken", "Salad"),
            results.map { it.title },
        )
    }

    // -----------------------------------------------------------------------
    // 8. Saved meal and logged meal with the same title → only the Saved appears
    // -----------------------------------------------------------------------

    @Test
    fun `logged result is suppressed when a saved meal has the same title`() = runTest {
        repo.saveMeal("Omelette", listOf(NewFoodItem("Eggs", 150.0, 12.0, 10.0, 1.0, null)))
        repo.addFoodGroup(
            listOf(NewFoodItem("Eggs", 150.0, 12.0, 10.0, 1.0, null)),
            mealName = "Omelette",
        )

        val results = repo.searchMeals("omelette")

        assertEquals(1, results.size)
        assertTrue(results[0] is MealSearchResult.Saved)
    }

    // -----------------------------------------------------------------------
    // 9. Soft-deleted entries never surface
    // -----------------------------------------------------------------------

    @Test
    fun `soft-deleted entries never surface`() = runTest {
        repo.addFood(name = "Yogurt", kcal = 100.0, proteinG = 10.0, fatG = 0.0, carbG = 8.0)
        val entry = db.foodEntryDao().getActive(userId).first { it.name == "Yogurt" }
        repo.softDeleteFood(entry.id)

        val results = repo.searchMeals("yogurt")

        assertTrue(results.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 10. Another user's rows never surface
    // -----------------------------------------------------------------------

    @Test
    fun `another user's saved meals and food entries never surface`() = runTest {
        val otherUserId = "other-user"
        db.foodEntryDao().insert(
            FoodEntryEntity(
                userId = otherUserId,
                timestamp = fixedNow,
                rawText = "Pizza",
                name = "Pizza",
                quantity = 1.0,
                unit = "serving",
                grams = 200.0,
                kcal = 500.0,
                proteinG = 20.0,
                fatG = 20.0,
                carbG = 50.0,
                sourceDb = FoodSourceDb.MANUAL,
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )
        )
        db.savedMealDao().insert(
            SavedMealEntity(
                userId = otherUserId,
                name = "Pizza Party",
                items = listOf(SavedMealItem("Pizza", 500.0, 20.0, 20.0, 50.0, 200.0)),
                createdAt = fixedNow,
            )
        )

        val results = repo.searchMeals("pizza")

        assertTrue(results.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 11. LIKE metacharacters (%, _, \) are treated literally
    // -----------------------------------------------------------------------

    @Test
    fun `percent and wildcard characters in the query are matched literally`() = runTest {
        repo.addFood(name = "100% Whey", kcal = 120.0, proteinG = 24.0, fatG = 1.0, carbG = 2.0)
        repo.addFood(name = "abc", kcal = 50.0, proteinG = 0.0, fatG = 0.0, carbG = 10.0)

        val literalMatch = repo.searchMeals("100%")
        assertEquals(1, literalMatch.size)
        assertEquals("100% Whey", literalMatch[0].title)

        val wildcardLeak = repo.searchMeals("a%c")
        assertTrue("'a%c' must not match 'abc' via unescaped LIKE wildcard", wildcardLeak.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 12. Blank query → all saved meals, newest first
    // -----------------------------------------------------------------------

    @Test
    fun `blank query returns all saved meals newest first`() = runTest {
        repo.saveMeal("First", listOf(NewFoodItem("A", 100.0, 0.0, 0.0, 0.0, null)))
        repo.saveMeal("Second", listOf(NewFoodItem("B", 200.0, 0.0, 0.0, 0.0, null)))

        val results = repo.searchMeals("   ")

        assertEquals(2, results.size)
        assertEquals("Second", results[0].title)
        assertEquals("First", results[1].title)
        assertTrue(results.all { it is MealSearchResult.Saved })
    }

    // -----------------------------------------------------------------------
    // 13. limit is respected
    // -----------------------------------------------------------------------

    @Test
    fun `limit caps the number of results for blank and non-blank queries`() = runTest {
        repeat(5) { i -> repo.saveMeal("Meal $i", listOf(NewFoodItem("Item $i", 100.0, 0.0, 0.0, 0.0, null))) }

        val blankResults = repo.searchMeals("", limit = 3)
        assertEquals(3, blankResults.size)

        val queryResults = repo.searchMeals("Meal", limit = 2)
        assertEquals(2, queryResults.size)
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
