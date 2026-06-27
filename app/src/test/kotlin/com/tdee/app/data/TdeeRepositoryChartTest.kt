package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Projection
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests for the chart-data methods added to [TdeeRepository]:
 *   - [TdeeRepository.weightSeries]
 *   - [TdeeRepository.expenditureSeries]
 *   - [TdeeRepository.macroSummary]
 *   - [TdeeRepository.weightProjection]
 *
 * Uses an in-memory Room DB, a fake CurrentUser, and a fixed Clock (UTC, dayStartHour = 0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryChartTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC

    // Fixed "now" = 2026-06-22T12:00:00Z → log-day = 2026-06-22
    private val fixedNow = Instant.parse("2026-06-22T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val today = LocalDate.of(2026, 6, 22)

    private val userId = "chart-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

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
    // Helpers
    // -----------------------------------------------------------------------

    /** Seeds a user profile with [goalWeightKg] optionally set. */
    private suspend fun seedProfile(goalWeightKg: Double? = 75.0) {
        db.userProfileDao().upsert(
            UserProfileEntity(
                userId = userId,
                sex = Sex.MALE,
                birthYear = 1990,
                heightCm = 175.0,
                activityLevel = ActivityLevel.MODERATE,
                goalRateKgPerWeek = -0.25,
                goalWeightKg = goalWeightKg,
                dayStartHour = 0,
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
        )
    }

    /** Inserts a weight entry at the given [date] at 08:00 UTC. */
    private suspend fun insertWeight(date: LocalDate, weightKg: Double) {
        val ts = date.atStartOfDay(zone).toInstant().plusSeconds(8 * 3600)
        db.weightEntryDao().insert(
            WeightEntryEntity(
                userId = userId,
                timestamp = ts,
                weightKg = weightKg,
                source = WeightSource.MANUAL,
                createdAt = ts,
            )
        )
    }

    /**
     * Inserts a single food entry on [date] at 13:00 UTC with the given macros.
     * By default marks a "complete" day (one entry = complete in our mapper).
     */
    private suspend fun insertFood(
        date: LocalDate,
        kcal: Double,
        proteinG: Double = 150.0,
        fatG: Double = 60.0,
        carbG: Double = 200.0,
    ) {
        val ts = date.atStartOfDay(zone).toInstant().plusSeconds(13 * 3600)
        db.foodEntryDao().insert(
            FoodEntryEntity(
                userId = userId,
                timestamp = ts,
                rawText = "test",
                name = "Test Food",
                quantity = 1.0,
                unit = "serving",
                grams = 300.0,
                kcal = kcal,
                proteinG = proteinG,
                fatG = fatG,
                carbG = carbG,
                sourceDb = FoodSourceDb.MANUAL,
                createdAt = ts,
                updatedAt = ts,
            )
        )
    }

    // -----------------------------------------------------------------------
    // weightSeries()
    // -----------------------------------------------------------------------

    @Test
    fun `weightSeries returns empty list when no weight entries`() = runTest {
        seedProfile()
        val series = repo.weightSeries()
        assertTrue("Should be empty with no weight entries", series.isEmpty())
    }

    @Test
    fun `weightSeries returns one point per log-day from first weight through today`() = runTest {
        seedProfile()
        val firstDay = today.minusDays(4)
        insertWeight(firstDay, 80.0)
        insertWeight(today.minusDays(2), 79.5)
        // today has no weight entry but should still appear

        val series = repo.weightSeries()

        // Expected: firstDay through today inclusive = 5 days
        assertEquals(5, series.size)
        assertEquals(firstDay, series.first().date)
        assertEquals(today, series.last().date)
    }

    @Test
    fun `weightSeries rawKg is null on days with no measurement`() = runTest {
        seedProfile()
        val day0 = today.minusDays(3)
        val day2 = today.minusDays(1)
        insertWeight(day0, 80.0)
        insertWeight(day2, 79.5)
        // day1 (today - 2) has no measurement

        val series = repo.weightSeries()
        val day1Point = series.find { it.date == today.minusDays(2) }
        assertNotNull("Should have a point for day1", day1Point)
        assertNull("rawKg should be null on a day with no measurement", day1Point!!.rawKg)
    }

    @Test
    fun `weightSeries rawKg is present on days with a measurement`() = runTest {
        seedProfile()
        insertWeight(today.minusDays(2), 80.0)

        val series = repo.weightSeries()
        val measured = series.find { it.date == today.minusDays(2) }
        assertNotNull(measured)
        assertEquals(80.0, measured!!.rawKg!!, 0.001)
    }

    @Test
    fun `weightSeries emaKg is finite for every point`() = runTest {
        seedProfile()
        insertWeight(today.minusDays(3), 80.0)
        insertWeight(today.minusDays(1), 79.5)

        val series = repo.weightSeries()
        assertTrue("All EMA values must be finite", series.all { it.emaKg.isFinite() })
    }

    @Test
    fun `weightSeries rawKg picks the earliest measurement on days with multiple entries`() = runTest {
        seedProfile()
        val day = today.minusDays(1)
        val earlyTs = day.atStartOfDay(zone).toInstant().plusSeconds(6 * 3600)  // 06:00
        val lateTs  = day.atStartOfDay(zone).toInstant().plusSeconds(20 * 3600) // 20:00
        db.weightEntryDao().insert(WeightEntryEntity(userId = userId, timestamp = earlyTs, weightKg = 80.0, source = WeightSource.MANUAL, createdAt = earlyTs))
        db.weightEntryDao().insert(WeightEntryEntity(userId = userId, timestamp = lateTs,  weightKg = 80.8, source = WeightSource.MANUAL, createdAt = lateTs))

        val series = repo.weightSeries()
        val point = series.find { it.date == day }
        assertNotNull(point)
        assertEquals("rawKg should be the earliest-in-day measurement", 80.0, point!!.rawKg!!, 0.001)
    }

    // -----------------------------------------------------------------------
    // expenditureSeries()
    // -----------------------------------------------------------------------

    @Test
    fun `expenditureSeries returns empty list when no weight entries`() = runTest {
        seedProfile()
        val series = repo.expenditureSeries()
        assertTrue(series.isEmpty())
    }

    @Test
    fun `expenditureSeries intakeKcal is null on days with no food entries`() = runTest {
        seedProfile()
        val day0 = today.minusDays(3)
        insertWeight(day0, 80.0)
        insertFood(today.minusDays(1), 2200.0) // only day2 has food

        val series = repo.expenditureSeries()
        val day0Point = series.find { it.date == day0 }
        assertNotNull(day0Point)
        assertNull("intakeKcal should be null on day with no food entries", day0Point!!.intakeKcal)
    }

    @Test
    fun `expenditureSeries intakeKcal is present on logged days`() = runTest {
        seedProfile()
        val day0 = today.minusDays(2)
        insertWeight(day0, 80.0)
        insertFood(day0, 2200.0)

        val series = repo.expenditureSeries()
        val point = series.find { it.date == day0 }
        assertNotNull(point)
        assertEquals(2200.0, point!!.intakeKcal!!, 0.001)
    }

    @Test
    fun `expenditureSeries tdeeKcal is finite for every point`() = runTest {
        seedProfile()
        insertWeight(today.minusDays(2), 80.0)

        val series = repo.expenditureSeries()
        assertTrue(series.all { it.tdeeKcal.isFinite() })
    }

    @Test
    fun `expenditureSeries calibrating is true early and false after full window`() = runTest {
        seedProfile()
        // Seed 15 weight entries + 15 food entries (> tdeeWindowDays=14).
        val startDay = today.minusDays(14)
        for (i in 0..14) {
            val day = startDay.plusDays(i.toLong())
            insertWeight(day, 80.0 - i * 0.05)
            insertFood(day, 2200.0)
        }

        val series = repo.expenditureSeries()
        // The first point should be calibrating (dataDays < window).
        assertTrue("First point should be calibrating", series.first().calibrating)
        // The last point (today) should not be calibrating (15 days of data, window=14).
        assertNotNull(series.find { !it.calibrating })
    }

    // -----------------------------------------------------------------------
    // macroSummary() — TODAY
    // -----------------------------------------------------------------------

    @Test
    fun `macroSummary TODAY returns today totals`() = runTest {
        seedProfile()
        insertWeight(today, 80.0)
        insertFood(today, kcal = 700.0, proteinG = 50.0, fatG = 30.0, carbG = 70.0)

        val summary = repo.macroSummary(ChartWindow.TODAY)

        assertEquals(700.0, summary.kcal, 0.001)
        assertEquals(50.0, summary.proteinG, 0.001)
        assertEquals(30.0, summary.fatG, 0.001)
        assertEquals(70.0, summary.carbG, 0.001)
        assertEquals(1, summary.completeDays)
        assertEquals(1, summary.totalDays)
    }

    @Test
    fun `macroSummary TODAY is zero when no food logged today`() = runTest {
        seedProfile()
        insertWeight(today, 80.0)

        val summary = repo.macroSummary(ChartWindow.TODAY)

        assertEquals(0.0, summary.kcal, 0.001)
    }

    // -----------------------------------------------------------------------
    // macroSummary() — windowed averages
    // -----------------------------------------------------------------------

    @Test
    fun `macroSummary M1 averages only complete days in window`() = runTest {
        seedProfile()
        insertWeight(today.minusDays(5), 80.0)

        // 3 complete days, 2 skipped, within the last month.
        insertFood(today.minusDays(4), 2000.0)
        insertFood(today.minusDays(3), 2200.0)
        insertFood(today.minusDays(1), 2400.0)
        // today.minusDays(5) and today.minusDays(2) are skipped (no food).

        val summary = repo.macroSummary(ChartWindow.M1)

        // Average kcal over 3 complete days = (2000 + 2200 + 2400) / 3 = 2200.
        assertEquals(2200.0, summary.kcal, 1.0)
        assertEquals(3, summary.completeDays)
        // totalDays = ~30 (M1 window); at least more than 3.
        assertTrue("totalDays should be >= completeDays", summary.totalDays >= summary.completeDays)
    }

    @Test
    fun `macroSummary completeDays and totalDays counts are correct`() = runTest {
        seedProfile()
        insertWeight(today.minusDays(10), 80.0)

        // Seed 5 complete days in the last 10.
        for (i in 1..5) {
            insertFood(today.minusDays(i.toLong()), 2000.0)
        }

        val summary = repo.macroSummary(ChartWindow.M1)

        assertEquals(5, summary.completeDays)
        assertTrue(summary.totalDays > 5)
    }

    @Test
    fun `macroSummary ALL returns zero fields when no food logged`() = runTest {
        seedProfile()
        insertWeight(today, 80.0)

        val summary = repo.macroSummary(ChartWindow.ALL)

        assertEquals(0.0, summary.kcal, 0.001)
        assertEquals(0, summary.completeDays)
    }

    // -----------------------------------------------------------------------
    // weightProjection()
    // -----------------------------------------------------------------------

    @Test
    fun `weightProjection returns null when no goalWeightKg set`() = runTest {
        seedProfile(goalWeightKg = null)
        insertWeight(today, 80.0)

        val result = repo.weightProjection()
        assertNull("Should return null when no goal weight", result)
    }

    @Test
    fun `weightProjection returns non-null when goalWeightKg is set`() = runTest {
        seedProfile(goalWeightKg = 75.0)
        insertWeight(today.minusDays(3), 80.0)

        val result = repo.weightProjection()
        assertNotNull(result)
    }

    @Test
    fun `weightProjection goalKg matches profile`() = runTest {
        seedProfile(goalWeightKg = 75.0)
        insertWeight(today.minusDays(3), 80.0)

        val result = repo.weightProjection()!!
        assertEquals(75.0, result.goalKg, 0.001)
    }

    @Test
    fun `weightProjection goalPace is Reachable for downward trend toward lower goal`() = runTest {
        // Profile has goalRateKgPerWeek = -0.25 (deficit), current trend ~80 kg, goal = 75 kg.
        seedProfile(goalWeightKg = 75.0)
        // Seed weight trend clearly above goal.
        for (i in 15 downTo 0) {
            insertWeight(today.minusDays(i.toLong()), 80.0 - i * 0.02)
        }

        val result = repo.weightProjection()!!
        assertTrue(
            "goalPace should be Reachable with deficit rate toward lower goal, got ${result.goalPace}",
            result.goalPace is Projection.Reachable,
        )
    }

    @Test
    fun `weightProjection currentPace is Unreachable when EMA is flat`() = runTest {
        // Flat weight → slope ≈ 0 → Unreachable.
        seedProfile(goalWeightKg = 75.0)
        for (i in 15 downTo 0) {
            insertWeight(today.minusDays(i.toLong()), 80.0) // completely flat
        }

        val result = repo.weightProjection()!!
        assertTrue(
            "currentPace should be Unreachable with flat EMA, got ${result.currentPace}",
            result.currentPace is Projection.Unreachable,
        )
    }

    @Test
    fun `weightProjection currentPace is Reachable when EMA trends toward goal`() = runTest {
        seedProfile(goalWeightKg = 75.0)
        // 15 days of clear downward trend: oldest day = 82 kg, most recent = ~80.5 kg.
        // i=0 → today.minusDays(15) = 82 kg; i=15 → today = 80.5 kg.
        for (i in 0..15) {
            insertWeight(today.minusDays((15 - i).toLong()), 82.0 - i * 0.1)
        }

        val result = repo.weightProjection()!!
        assertTrue(
            "currentPace should be Reachable with downward EMA trend toward lower goal, got ${result.currentPace}",
            result.currentPace is Projection.Reachable,
        )
    }

    @Test
    fun `weightProjection currentTrendKg is finite`() = runTest {
        seedProfile(goalWeightKg = 75.0)
        insertWeight(today.minusDays(5), 80.0)

        val result = repo.weightProjection()!!
        assertTrue(result.currentTrendKg.isFinite())
    }
}
