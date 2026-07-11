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
import java.time.ZoneOffset

/**
 * Tests for the in-place scaling operations added to [TdeeRepository]:
 *   - [TdeeRepository.scaleMeal]
 *   - [TdeeRepository.scaleFood]
 *
 * Uses an in-memory Room v3 database, a fake [CurrentUser], and a fixed [Clock].
 * Fixed "now" = 2026-06-21T12:00:00Z → log-day 2026-06-21 (dayStartHour = 0, UTC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryScaleTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "scale-test-user"
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
    // scaleMeal
    // -----------------------------------------------------------------------

    @Test
    fun `scaleMeal multiplies macros and grams of every row in the meal`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Apple", 100.0, 1.0, 2.0, 20.0, 150.0),
                NewFoodItem("Banana", 200.0, 2.0, 4.0, 40.0, 120.0),
            )
        )
        val before = db.foodEntryDao().getByMeal(userId, mealId)

        repo.scaleMeal(mealId, 1.5)

        val after = db.foodEntryDao().getByMeal(userId, mealId)
        assertEquals(2, after.size)
        after.forEachIndexed { i, entry ->
            val orig = before[i]
            assertEquals(orig.id, entry.id)
            assertEquals(orig.mealId, entry.mealId)
            assertEquals(orig.timestamp, entry.timestamp)
            assertEquals(orig.kcal * 1.5, entry.kcal, 0.001)
            assertEquals(orig.proteinG * 1.5, entry.proteinG, 0.001)
            assertEquals(orig.fatG * 1.5, entry.fatG, 0.001)
            assertEquals(orig.carbG * 1.5, entry.carbG, 0.001)
            assertEquals(orig.grams * 1.5, entry.grams, 0.001)
        }
    }

    @Test
    fun `scaleMeal does not touch entries from other meals or standalone`() = runTest {
        val mealId1 = repo.addFoodGroup(listOf(NewFoodItem("Apple", 100.0, 1.0, 2.0, 20.0, 150.0)))
        val mealId2 = repo.addFoodGroup(listOf(NewFoodItem("Chicken", 250.0, 30.0, 5.0, 0.0, 120.0)))
        repo.addFood(
            name = "Standalone", kcal = 50.0, proteinG = 1.0, fatG = 1.0, carbG = 5.0, grams = 10.0,
        )
        val standalone = db.foodEntryDao().getActive(userId).first { it.mealId == null }

        repo.scaleMeal(mealId1, 2.0)

        val other = db.foodEntryDao().getByMeal(userId, mealId2).first()
        assertEquals(250.0, other.kcal, 0.001)

        val standaloneAfter = db.foodEntryDao().getById(standalone.id)!!
        assertEquals(50.0, standaloneAfter.kcal, 0.001)
    }

    @Test
    fun `scaleMeal with factor 1_0 leaves values unchanged`() = runTest {
        val mealId = repo.addFoodGroup(listOf(NewFoodItem("Apple", 100.0, 1.0, 2.0, 20.0, 150.0)))
        val before = db.foodEntryDao().getByMeal(userId, mealId).first()

        repo.scaleMeal(mealId, 1.0)

        val after = db.foodEntryDao().getByMeal(userId, mealId).first()
        assertEquals(before.kcal, after.kcal, 0.001)
        assertEquals(before.proteinG, after.proteinG, 0.001)
        assertEquals(before.fatG, after.fatG, 0.001)
        assertEquals(before.carbG, after.carbG, 0.001)
        assertEquals(before.grams, after.grams, 0.001)
    }

    @Test
    fun `scaleMeal on an empty or unknown mealId is a no-op`() = runTest {
        // Should not throw
        repo.scaleMeal("does-not-exist", 2.0)
        assertTrue(db.foodEntryDao().getActive(userId).isEmpty())
    }

    // -----------------------------------------------------------------------
    // scaleFood
    // -----------------------------------------------------------------------

    @Test
    fun `scaleFood scales exactly one entry, others untouched`() = runTest {
        repo.addFood(name = "Apple", kcal = 100.0, proteinG = 1.0, fatG = 2.0, carbG = 20.0, grams = 150.0)
        repo.addFood(name = "Chicken", kcal = 250.0, proteinG = 30.0, fatG = 5.0, carbG = 0.0, grams = 120.0)
        val entries = db.foodEntryDao().getActive(userId)
        val apple = entries.first { it.name == "Apple" }
        val chicken = entries.first { it.name == "Chicken" }

        repo.scaleFood(apple.id, 2.0)

        val appleAfter = db.foodEntryDao().getById(apple.id)!!
        assertEquals(200.0, appleAfter.kcal, 0.001)
        assertEquals(2.0, appleAfter.proteinG, 0.001)
        assertEquals(4.0, appleAfter.fatG, 0.001)
        assertEquals(40.0, appleAfter.carbG, 0.001)
        assertEquals(300.0, appleAfter.grams, 0.001)

        val chickenAfter = db.foodEntryDao().getById(chicken.id)!!
        assertEquals(250.0, chickenAfter.kcal, 0.001)
    }

    @Test
    fun `scaleFood with an unknown id is a no-op`() = runTest {
        // Should not throw
        repo.scaleFood(9999L, 2.0)
    }

    @Test
    fun `scaleFood on an entry with grams 0_0 keeps grams 0_0`() = runTest {
        repo.addFood(name = "Coffee", kcal = 5.0, proteinG = 0.0, fatG = 0.0, carbG = 1.0, grams = null)
        val entry = db.foodEntryDao().getActive(userId).first()
        assertEquals(0.0, entry.grams, 0.001)

        repo.scaleFood(entry.id, 3.0)

        val after = db.foodEntryDao().getById(entry.id)!!
        assertEquals(0.0, after.grams, 0.001)
        assertEquals(15.0, after.kcal, 0.001)
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
