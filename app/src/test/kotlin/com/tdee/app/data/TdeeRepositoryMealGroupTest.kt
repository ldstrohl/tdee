package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Tests for meal-group operations added to [TdeeRepository]:
 *   - [TdeeRepository.addFoodGroup]
 *   - [TdeeRepository.addFoodItems]
 *   - [TdeeRepository.getFoodEntry]
 *   - [TdeeRepository.updateFood]
 *   - [TdeeRepository.softDeleteMeal]
 *
 * Uses an in-memory Room v3 database, a fake [CurrentUser], and a fixed [Clock].
 * Fixed "now" = 2026-06-21T12:00:00Z → log-day 2026-06-21 (dayStartHour = 0, UTC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryMealGroupTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "meal-group-test-user"
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
    // addFoodGroup
    // -----------------------------------------------------------------------

    @Test
    fun `addFoodGroup assigns one shared mealId to all inserted rows`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            )
        )

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(2, entries.size)
        assertEquals(mealId, entries[0].mealId)
        assertEquals(mealId, entries[1].mealId)
        assertNotNull(mealId)
        assertTrue("mealId should be a non-blank UUID", mealId.isNotBlank())
    }

    @Test
    fun `addFoodGroup stores all item fields correctly`() = runTest {
        repo.addFoodGroup(
            listOf(
                NewFoodItem("Chicken", 250.0, 30.0, 5.0, 0.0, 120.0),
            )
        )

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("Chicken", e.name)
        assertEquals(250.0, e.kcal, 0.001)
        assertEquals(30.0, e.proteinG, 0.001)
        assertEquals(5.0, e.fatG, 0.001)
        assertEquals(0.0, e.carbG, 0.001)
        assertEquals(120.0, e.grams, 0.001)
        assertEquals(userId, e.userId)
        assertEquals(FoodSourceDb.MANUAL, e.sourceDb)
    }

    @Test
    fun `addFoodGroup items share the same timestamp`() = runTest {
        repo.addFoodGroup(
            listOf(
                NewFoodItem("A", 100.0, 0.0, 0.0, 0.0, null),
                NewFoodItem("B", 200.0, 0.0, 0.0, 0.0, null),
            )
        )

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(2, entries.size)
        assertEquals(entries[0].timestamp, entries[1].timestamp)
    }

    @Test
    fun `addFoodGroup returns distinct mealIds for separate calls`() = runTest {
        val id1 = repo.addFoodGroup(listOf(NewFoodItem("A", 100.0, 0.0, 0.0, 0.0, null)))
        val id2 = repo.addFoodGroup(listOf(NewFoodItem("B", 200.0, 0.0, 0.0, 0.0, null)))

        assertTrue("Two separate calls should produce different mealIds", id1 != id2)
    }

    @Test
    fun `addFoodGroup entries are not visible under a different userId`() = runTest {
        repo.addFoodGroup(listOf(NewFoodItem("Rice", 200.0, 4.0, 1.0, 44.0, null)))

        val other = db.foodEntryDao().getActive("other-user")
        assertTrue(other.isEmpty())
    }

    @Test
    fun `addFoodGroup persists each item's factor as scaleFactor`() = runTest {
        repo.addFoodGroup(
            listOf(
                NewFoodItem("Rice", 200.0, 4.0, 1.0, 44.0, null, factor = 2.0),
                NewFoodItem("Egg", 70.0, 6.0, 5.0, 0.0, null),
            )
        )

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(2.0, entries.first { it.name == "Rice" }.scaleFactor, 0.001)
        assertEquals(1.0, entries.first { it.name == "Egg" }.scaleFactor, 0.001)
    }

    // -----------------------------------------------------------------------
    // addFoodItems
    // -----------------------------------------------------------------------

    @Test
    fun `addFoodItems inserts standalone entries with null mealId and mealName`() = runTest {
        repo.addFoodItems(
            listOf(
                NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null, factor = 1.5),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            )
        )

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(2, entries.size)
        entries.forEach {
            assertNull(it.mealId)
            assertNull(it.mealName)
        }
        assertEquals(entries[0].timestamp, entries[1].timestamp)
        assertEquals(1.5, entries.first { it.name == "Apple" }.scaleFactor, 0.001)
        assertEquals(1.0, entries.first { it.name == "Banana" }.scaleFactor, 0.001)
    }

    @Test
    fun `addFoodItems with empty list is a no-op`() = runTest {
        repo.addFoodItems(emptyList())

        assertTrue(db.foodEntryDao().getActive(userId).isEmpty())
    }

    // -----------------------------------------------------------------------
    // getFoodEntry
    // -----------------------------------------------------------------------

    @Test
    fun `getFoodEntry returns the entry with the given id`() = runTest {
        val mealId = repo.addFoodGroup(listOf(NewFoodItem("Oats", 300.0, 10.0, 5.0, 55.0, null)))
        val inserted = db.foodEntryDao().getActive(userId).first()

        val fetched = repo.getFoodEntry(inserted.id)
        assertNotNull(fetched)
        assertEquals("Oats", fetched!!.name)
        assertEquals(mealId, fetched.mealId)
    }

    @Test
    fun `getFoodEntry returns null for an unknown id`() = runTest {
        val fetched = repo.getFoodEntry(9999L)
        assertNull(fetched)
    }

    // -----------------------------------------------------------------------
    // updateFood
    // -----------------------------------------------------------------------

    @Test
    fun `updateFood changes name and macros`() = runTest {
        val mealId = repo.addFoodGroup(listOf(NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null)))
        val original = db.foodEntryDao().getActive(userId).first()

        repo.updateFood(original.id, "Updated Apple", 100.0, 1.0, 0.5, 26.0, 150.0)

        val updated = db.foodEntryDao().getById(original.id)!!
        assertEquals("Updated Apple", updated.name)
        assertEquals(100.0, updated.kcal, 0.001)
        assertEquals(1.0, updated.proteinG, 0.001)
        assertEquals(0.5, updated.fatG, 0.001)
        assertEquals(26.0, updated.carbG, 0.001)
        assertEquals(150.0, updated.grams, 0.001)
    }

    @Test
    fun `updateFood preserves userId, timestamp, createdAt, mealId, and rawText`() = runTest {
        val mealId = repo.addFoodGroup(listOf(NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null)))
        val original = db.foodEntryDao().getActive(userId).first()

        repo.updateFood(original.id, "Updated Apple", 100.0, 1.0, 0.5, 26.0, null)

        val updated = db.foodEntryDao().getById(original.id)!!
        assertEquals(original.userId, updated.userId)
        assertEquals(original.timestamp, updated.timestamp)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(mealId, updated.mealId)
        // rawText must not change
        assertEquals("Apple", updated.rawText)
    }

    @Test
    fun `updateFood sets updatedAt to clock instant`() = runTest {
        repo.addFoodGroup(listOf(NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null)))
        val original = db.foodEntryDao().getActive(userId).first()

        repo.updateFood(original.id, "Updated", 100.0, 0.0, 0.0, 0.0, null)

        val updated = db.foodEntryDao().getById(original.id)!!
        assertEquals(fixedNow, updated.updatedAt)
    }

    @Test
    fun `updateFood resets scaleFactor to 1point0`() = runTest {
        repo.addFoodGroup(listOf(NewFoodItem("Rice", 200.0, 4.0, 1.0, 44.0, null, factor = 2.0)))
        val original = db.foodEntryDao().getActive(userId).first()
        assertEquals(2.0, original.scaleFactor, 0.001)

        repo.updateFood(original.id, "Updated Rice", 100.0, 2.0, 0.5, 22.0, null)

        val updated = db.foodEntryDao().getById(original.id)!!
        assertEquals(1.0, updated.scaleFactor, 0.001)
    }

    @Test
    fun `updateFood is a no-op for unknown id`() = runTest {
        // Should not throw
        repo.updateFood(9999L, "x", 100.0, 0.0, 0.0, 0.0, null)
        // DB remains empty
        assertTrue(db.foodEntryDao().getActive(userId).isEmpty())
    }

    // -----------------------------------------------------------------------
    // softDeleteMeal
    // -----------------------------------------------------------------------

    @Test
    fun `softDeleteMeal soft-deletes all rows in the group`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            )
        )

        repo.softDeleteMeal(mealId)

        val active = db.foodEntryDao().getActive(userId)
        assertTrue("All meal entries should be soft-deleted", active.isEmpty())
    }

    @Test
    fun `softDeleteMeal does not affect rows from other groups`() = runTest {
        val mealId1 = repo.addFoodGroup(
            listOf(
                NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            )
        )
        val mealId2 = repo.addFoodGroup(
            listOf(
                NewFoodItem("Chicken", 250.0, 30.0, 5.0, 0.0, null),
            )
        )

        repo.softDeleteMeal(mealId1)

        val active = db.foodEntryDao().getActive(userId)
        assertEquals(1, active.size)
        assertEquals(mealId2, active[0].mealId)
        assertEquals("Chicken", active[0].name)
    }

    @Test
    fun `softDeleteMeal sets deletedAt to clock instant for each row`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("A", 100.0, 0.0, 0.0, 0.0, null),
                NewFoodItem("B", 200.0, 0.0, 0.0, 0.0, null),
            )
        )

        repo.softDeleteMeal(mealId)

        val all = db.foodEntryDao().getAll(userId)
        assertTrue(all.all { it.deletedAt == fixedNow })
    }

    // -----------------------------------------------------------------------
    // mealName persistence (N3)
    // -----------------------------------------------------------------------

    @Test
    fun `addFoodGroup with mealName stamps all rows with that name`() = runTest {
        repo.addFoodGroup(
            listOf(
                NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            ),
            mealName = "Breakfast",
        )

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(2, entries.size)
        assertEquals("Breakfast", entries[0].mealName)
        assertEquals("Breakfast", entries[1].mealName)
    }

    @Test
    fun `addFoodGroup without mealName leaves mealName null on all rows`() = runTest {
        repo.addFoodGroup(
            listOf(NewFoodItem("Oats", 300.0, 10.0, 5.0, 55.0, null)),
        )

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(1, entries.size)
        assertNull(entries[0].mealName)
    }

    // -----------------------------------------------------------------------
    // renameMeal / renameFood
    // -----------------------------------------------------------------------

    @Test
    fun `renameMeal stamps the new name on every row of the group`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(
                NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null),
                NewFoodItem("Banana", 105.0, 1.3, 0.4, 27.0, null),
            ),
            mealName = "Breakfast",
        )

        repo.renameMeal(mealId, "Brunch")

        val entries = db.foodEntryDao().getActive(userId)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.mealName == "Brunch" })
        assertTrue(entries.all { it.updatedAt == fixedNow })
    }

    @Test
    fun `renameMeal names a group that had no name`() = runTest {
        val mealId = repo.addFoodGroup(
            listOf(NewFoodItem("Oats", 300.0, 10.0, 5.0, 55.0, null)),
        )

        repo.renameMeal(mealId, "Morning oats")

        assertEquals("Morning oats", db.foodEntryDao().getActive(userId).first().mealName)
    }

    @Test
    fun `renameMeal does not affect other groups`() = runTest {
        val mealId1 = repo.addFoodGroup(
            listOf(NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null)),
            mealName = "Breakfast",
        )
        repo.addFoodGroup(
            listOf(NewFoodItem("Chicken", 250.0, 30.0, 5.0, 0.0, null)),
            mealName = "Lunch",
        )

        repo.renameMeal(mealId1, "Brunch")

        val names = db.foodEntryDao().getActive(userId).map { it.mealName }.toSet()
        assertEquals(setOf("Brunch", "Lunch"), names)
    }

    @Test
    fun `renameFood changes only the name and bumps updatedAt`() = runTest {
        repo.addFoodGroup(listOf(NewFoodItem("Apple", 95.0, 0.5, 0.3, 25.0, null)))
        val original = db.foodEntryDao().getActive(userId).first()

        repo.renameFood(original.id, "Green apple")

        val updated = db.foodEntryDao().getById(original.id)!!
        assertEquals("Green apple", updated.name)
        assertEquals(original.kcal, updated.kcal, 0.001)
        assertEquals(original.proteinG, updated.proteinG, 0.001)
        assertEquals(original.mealId, updated.mealId)
        assertEquals(original.rawText, updated.rawText)
        assertEquals(fixedNow, updated.updatedAt)
    }

    @Test
    fun `renameFood is a no-op for unknown id`() = runTest {
        repo.renameFood(9999L, "x")
        assertTrue(db.foodEntryDao().getActive(userId).isEmpty())
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
