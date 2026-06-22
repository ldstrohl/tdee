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
 * Tests for the backdated-logging overloads introduced in Module 10:
 *   - [TdeeRepository.addFood] with [loggedDate]
 *   - [TdeeRepository.addWeight] with [loggedDate]
 *
 * Fixed "now" = 2026-06-21T12:00:00Z → log-day 2026-06-21 (dayStartHour = 0, UTC).
 * Past date under test = 2026-06-15, which is 6 days prior to "now".
 *
 * The backdated timestamp formula is:
 *   loggedDate.atStartOfDay(UTC).toInstant() + (dayStartHour + 12) * 3600 seconds
 * For dayStartHour=0 and UTC this places the entry at 2026-06-15T12:00:00Z, whose
 * log-day (t - 0h) -> date in UTC = 2026-06-15. Round-trips correctly via logDay().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryBackdateTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val today = LocalDate.of(2026, 6, 21)
    private val pastDate = LocalDate.of(2026, 6, 15)

    private val userId = "backdate-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    @Before
    fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.userProfileDao().upsert(
            UserProfileEntity(
                userId = userId,
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
    }

    @After
    fun teardown() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // addFood with loggedDate
    // -----------------------------------------------------------------------

    @Test
    fun `addFood with loggedDate places entry on that log-day`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
            loggedDate = pastDate,
        )

        val all = db.foodEntryDao().getActive(userId)
        assertEquals(1, all.size)
        val entry = all[0]
        // Verify the timestamp round-trips to pastDate via logDay.
        val resolvedDay = logDay(entry.timestamp, zone, dayStartHour = 0)
        assertEquals("Entry log-day should equal pastDate", pastDate, resolvedDay)
    }

    @Test
    fun `addFood with loggedDate does NOT appear in todayFoodEntries`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
            loggedDate = pastDate,
        )

        val todayEntries = repo.todayFoodEntries()
        assertTrue("Past-dated entry must not show up in today's list", todayEntries.isEmpty())
    }

    @Test
    fun `addFood with loggedDate IS found via a range query on that day`() = runTest {
        repo.addFood(
            name = "Oats",
            kcal = 300.0,
            proteinG = 10.0,
            fatG = 5.0,
            carbG = 55.0,
            loggedDate = pastDate,
        )

        // Build the window for pastDate (dayStartHour=0, UTC): [2026-06-15T00:00Z, 2026-06-16T00:00Z).
        val windowStart = pastDate.atStartOfDay(zone).toInstant()
        val windowEnd = windowStart.plusSeconds(24 * 3600L)
        val rangeEntries = db.foodEntryDao().getActiveInRange(userId, windowStart, windowEnd)
        assertEquals("Entry should be found in past-day window query", 1, rangeEntries.size)
        assertEquals(300.0, rangeEntries[0].kcal, 0.001)
    }

    @Test
    fun `addFood with null loggedDate lands on today as before`() = runTest {
        repo.addFood(
            name = "Apple",
            kcal = 95.0,
            proteinG = 0.5,
            fatG = 0.3,
            carbG = 25.0,
            loggedDate = null,
        )

        val todayEntries = repo.todayFoodEntries()
        assertEquals(1, todayEntries.size)
        val resolvedDay = logDay(todayEntries[0].timestamp, zone, dayStartHour = 0)
        assertEquals("Entry log-day should equal today", today, resolvedDay)
    }

    @Test
    fun `addFood backdated timestamp noon formula round-trips for dayStartHour 0`() = runTest {
        repo.addFood(
            name = "X",
            kcal = 100.0,
            proteinG = 0.0,
            fatG = 0.0,
            carbG = 0.0,
            loggedDate = pastDate,
        )
        val entry = db.foodEntryDao().getActive(userId)[0]
        // For dayStartHour=0 the formula gives: pastDate at 00:00 UTC + 12h = pastDate at 12:00 UTC.
        val expected = Instant.parse("2026-06-15T12:00:00Z")
        assertEquals(expected, entry.timestamp)
    }

    // -----------------------------------------------------------------------
    // addWeight with loggedDate
    // -----------------------------------------------------------------------

    @Test
    fun `addWeight with loggedDate places entry on that log-day`() = runTest {
        repo.addWeight(175.0, loggedDate = pastDate)

        val all = db.weightEntryDao().getAll(userId)
        assertEquals(1, all.size)
        val resolvedDay = logDay(all[0].timestamp, zone, dayStartHour = 0)
        assertEquals("Weight entry log-day should equal pastDate", pastDate, resolvedDay)
    }

    @Test
    fun `addWeight with null loggedDate timestamps at clock instant`() = runTest {
        repo.addWeight(175.0, loggedDate = null)

        val all = db.weightEntryDao().getAll(userId)
        assertEquals(1, all.size)
        val resolvedDay = logDay(all[0].timestamp, zone, dayStartHour = 0)
        assertEquals("Weight entry log-day should equal today when loggedDate is null", today, resolvedDay)
    }

    @Test
    fun `addWeight backdated converts lb to kg correctly`() = runTest {
        repo.addWeight(154.0, loggedDate = pastDate)

        val all = db.weightEntryDao().getAll(userId)
        assertEquals(154.0 * 0.45359237, all[0].weightKg, 0.0001)
    }
}
