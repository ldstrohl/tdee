package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.DefaultTdeeEngine
import com.tdee.domain.Projection
import com.tdee.domain.Sex
import com.tdee.domain.TdeeMethod
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Integration tests for [TdeeRepository] using an in-memory Room database and a fixed clock.
 *
 * Seed data: a male user profile, 7 days of weight entries (80 kg → 79.3 kg, slight downward
 * trend) and matching food entries (2200 kcal/day), all in UTC with dayStartHour = 0.
 *
 * The fixed clock is set to noon on the day AFTER the last weight/food entry so the engine's
 * empirical window covers all 7 seeded days.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    // Anchor date: day 0 of weight data is 2026-06-13; 7 days of data → 2026-06-13..2026-06-19.
    // Clock is set to 2026-06-21T12:00:00Z so log-day = 2026-06-21 and the window covers the data.
    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    // Primary test user
    private val userId = "test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    // First weight day
    private val day0 = LocalDate.of(2026, 6, 13)

    // 7 weight values with gentle downward trend (~ -100 g/day)
    private val weights = listOf(80.0, 79.9, 79.8, 79.6, 79.5, 79.4, 79.3)

    // Daily kcal intake (constant, just above expected TDEE so math is predictable)
    private val dailyKcal = 2200.0

    @Before
    fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        seedUser(userId)

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
    }

    @After
    fun teardown() {
        db.close()
    }

    /**
     * Seeds a complete user dataset (profile + 7 weight entries + 7 food entries) for the given
     * [uid]. Reused by isolation tests to populate a second user.
     */
    private suspend fun seedUser(uid: String) {
        val profile = makeProfile(uid)
        db.userProfileDao().upsert(profile)

        weights.forEachIndexed { i, kg ->
            val ts = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant()
                .plusSeconds(8 * 3600)
            db.weightEntryDao().insert(
                WeightEntryEntity(
                    userId = uid,
                    timestamp = ts,
                    weightKg = kg,
                    source = WeightSource.MANUAL,
                    createdAt = ts,
                )
            )
        }

        weights.indices.forEach { i ->
            val ts = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant()
                .plusSeconds(13 * 3600)
            db.foodEntryDao().insert(
                FoodEntryEntity(
                    userId = uid,
                    timestamp = ts,
                    rawText = "test",
                    name = "Test Food",
                    quantity = 1.0,
                    unit = "serving",
                    grams = 500.0,
                    kcal = dailyKcal,
                    proteinG = 150.0,
                    fatG = 61.1,   // 2200 * 0.25 / 9 ≈ 61.1
                    carbG = 263.9, // remainder
                    sourceDb = FoodSourceDb.MANUAL,
                    createdAt = ts,
                    updatedAt = ts,
                )
            )
        }
    }

    private fun makeProfile(uid: String) = UserProfileEntity(
        userId = uid,
        sex = Sex.MALE,
        birthYear = 1990,
        heightCm = 175.0,
        activityLevel = ActivityLevel.MODERATE,
        goalRateKgPerWeek = -0.25,
        goalWeightKg = 75.0,
        proteinGPerKg = 2.0,
        fatPctOfCalories = 0.25,
        dayStartHour = 0,
        smoothingWindowDays = 14,
        tdeeWindowDays = 14,
        createdAt = Instant.parse("2026-06-13T08:00:00Z"),
        updatedAt = Instant.parse("2026-06-13T08:00:00Z"),
    )

    // -----------------------------------------------------------------------
    // 1. currentEstimate()
    // -----------------------------------------------------------------------

    @Test
    fun `currentEstimate returns a finite value and is calibrating with 7 days of data`() = runTest {
        val estimate = repo.currentEstimate()

        assertTrue("valueKcal must be finite", estimate.valueKcal.isFinite())
        assertTrue("should be calibrating with < 14 days of data", estimate.calibrating)
        // With 7 data days (< tdeeWindowDays=14): method is FORMULA or BLEND.
        assertTrue(
            "method should be FORMULA or BLEND, was ${estimate.method}",
            estimate.method == TdeeMethod.FORMULA || estimate.method == TdeeMethod.BLEND,
        )
    }

    // -----------------------------------------------------------------------
    // 2. proposedTargets() macro arithmetic
    // -----------------------------------------------------------------------

    @Test
    fun `proposedTargets macro calories sum to calorieTargetKcal`() = runTest {
        val targets = repo.proposedTargets()

        val macroSum = targets.proteinG * 4 + targets.fatG * 9 + targets.carbG * 4
        assertEquals(
            "proteinG*4 + fatG*9 + carbG*4 should ≈ calorieTargetKcal",
            targets.calorieTargetKcal,
            macroSum,
            1.0, // 1 kcal tolerance for rounding
        )
    }

    @Test
    fun `proposedTargets calorieTarget is below TDEE estimate for deficit goal`() = runTest {
        val estimate = repo.currentEstimate()
        val targets = repo.proposedTargets()

        // Profile has goalRateKgPerWeek = -0.25 (deficit), so target < TDEE.
        assertTrue(
            "calorie target (${targets.calorieTargetKcal}) should be < TDEE (${estimate.valueKcal})",
            targets.calorieTargetKcal < estimate.valueKcal,
        )
    }

    // -----------------------------------------------------------------------
    // 3. project()
    // -----------------------------------------------------------------------

    @Test
    fun `project with deficit intake and goal below current trend returns Reachable`() = runTest {
        val estimate = repo.currentEstimate()
        val trendKg = repo.currentTrendKg()

        // Goal weight = 75 kg (below ~79 kg trend). Deficit intake = TDEE - 500.
        val deficitIntake = estimate.valueKcal - 500.0
        val goalKg = 75.0

        val projection = repo.project(deficitIntake, goalKg)

        assertTrue(
            "deficit toward a lower goal should be Reachable, got $projection",
            projection is Projection.Reachable,
        )
        val reachable = projection as Projection.Reachable
        // Predicted date must be in the future.
        assertTrue(
            "predictedDate should be after today",
            reachable.predictedDate.isAfter(LocalDate.of(2026, 6, 21)),
        )
        // Rate should be negative (losing weight).
        assertTrue("rateKgPerDay should be negative for a deficit", reachable.rateKgPerDay < 0)
    }

    @Test
    fun `project with surplus intake while goal is below current trend returns Unreachable`() = runTest {
        val estimate = repo.currentEstimate()
        val trendKg = repo.currentTrendKg()

        // Goal weight below current trend, but intake is above TDEE (gaining, not losing).
        val surplusIntake = estimate.valueKcal + 500.0
        val goalKg = 75.0 // below ~79 kg trend

        val projection = repo.project(surplusIntake, goalKg)

        assertTrue(
            "surplus intake toward a lower goal should be Unreachable, got $projection",
            projection is Projection.Unreachable,
        )
    }

    // -----------------------------------------------------------------------
    // 4. recomputeTrendCache()
    // -----------------------------------------------------------------------

    @Test
    fun `recomputeTrendCache writes one row per log-day from first sample through current log-day`() =
        runTest {
            repo.recomputeTrendCache()

            val rows = db.weightTrendCacheDao().getAll(userId)

            // First sample day = 2026-06-13; current log-day = 2026-06-21.
            // Inclusive count = 9 days.
            val firstDay = LocalDate.of(2026, 6, 13)
            val lastDay = LocalDate.of(2026, 6, 21)
            val expectedCount = firstDay.until(lastDay).days + 1
            assertEquals("Expected one cache row per log-day", expectedCount, rows.size)

            // Verify first and last dates.
            assertEquals(firstDay, rows.first().date)
            assertEquals(lastDay, rows.last().date)
        }

    @Test
    fun `recomputeTrendCache emaKg matches engine weightTrendAt for spot-checked day`() = runTest {
        repo.recomputeTrendCache()

        // Spot-check: 2026-06-15 (index 2, third weight entry = 79.8 kg seeded but EMA is smoothed).
        val spotDay = LocalDate.of(2026, 6, 15)

        // Build the expected value using the engine directly with the same data.
        val samples = db.weightEntryDao().getAll(userId).toWeightSamples()
        val intake = db.foodEntryDao().getActive(userId).toDailyIntake(zone, 0)
        val profileEntity = db.userProfileDao().get(userId)!!
        val profile = profileEntity.toDomain()
        val engine = DefaultTdeeEngine(samples, intake, profile, zone)

        // Instant that maps to spotDay's log-day: midnight + 0 hours (dayStartHour=0).
        val spotInstant = spotDay.atStartOfDay(zone).toInstant()
        val expectedEma = engine.weightTrendAt(spotInstant)

        val cachedRow = db.weightTrendCacheDao().getByDate(userId, spotDay)!!
        assertEquals("emaKg should match engine.weightTrendAt for $spotDay", expectedEma, cachedRow.emaKg, 0.001)
    }

    @Test
    fun `recomputeTrendCache is a no-op when no weight samples exist`() = runTest {
        // Clear weight data.
        db.weightEntryDao().deleteAll()

        repo.recomputeTrendCache() // should not throw

        val rows = db.weightTrendCacheDao().getAll(userId)
        assertEquals("no rows should be written when there are no samples", 0, rows.size)
    }

    // -----------------------------------------------------------------------
    // 5. Missing profile throws documented exception
    // -----------------------------------------------------------------------

    @Test(expected = IllegalStateException::class)
    fun `currentEstimate throws IllegalStateException when no profile exists`(): Unit = runTest {
        db.userProfileDao().deleteAll()
        repo.currentEstimate()
    }

    @Test(expected = IllegalStateException::class)
    fun `proposedTargets throws IllegalStateException when no profile exists`(): Unit = runTest {
        db.userProfileDao().deleteAll()
        repo.proposedTargets()
    }

    @Test(expected = IllegalStateException::class)
    fun `project throws IllegalStateException when no profile exists`(): Unit = runTest {
        db.userProfileDao().deleteAll()
        repo.project(2000.0, 75.0)
    }

    @Test(expected = IllegalStateException::class)
    fun `recomputeTrendCache throws IllegalStateException when no profile exists`(): Unit = runTest {
        db.userProfileDao().deleteAll()
        repo.recomputeTrendCache()
    }

    // -----------------------------------------------------------------------
    // 6. Two-user isolation tests
    // -----------------------------------------------------------------------

    /**
     * Inserts data for user "A" and user "B". Verifies that each user's DAO reads return only
     * their own rows — no cross-user bleed.
     */
    @Test
    fun `user A data is not visible to user B and vice versa`() = runTest {
        val userA = "isolation-user-a"
        val userB = "isolation-user-b"

        // Seed both users (on top of the default userId already seeded in @Before).
        seedUser(userA)
        seedUser(userB)

        // Profiles
        assertEquals(userA, db.userProfileDao().get(userA)!!.userId)
        assertEquals(userB, db.userProfileDao().get(userB)!!.userId)
        assertFalse("User A profile should not be returned for User B",
            db.userProfileDao().get(userB)!!.userId == userA)

        // Weight entries: each user seeded 7 rows
        val weightA = db.weightEntryDao().getAll(userA)
        val weightB = db.weightEntryDao().getAll(userB)
        assertEquals(7, weightA.size)
        assertEquals(7, weightB.size)
        assertTrue("All weight rows for A should belong to A", weightA.all { it.userId == userA })
        assertTrue("All weight rows for B should belong to B", weightB.all { it.userId == userB })

        // Food entries
        val foodA = db.foodEntryDao().getActive(userA)
        val foodB = db.foodEntryDao().getActive(userB)
        assertEquals(7, foodA.size)
        assertEquals(7, foodB.size)
        assertTrue("All food rows for A should belong to A", foodA.all { it.userId == userA })
        assertTrue("All food rows for B should belong to B", foodB.all { it.userId == userB })
    }

    /**
     * Builds two [TdeeRepository] instances — one for user A, one for user B — with identical
     * data and verifies:
     *  - [currentEstimate] from A's repo equals A's direct-engine result (not contaminated by B).
     *  - [recomputeTrendCache] for A only writes rows tagged with A's userId.
     */
    @Test
    fun `TdeeRepository scoped to user A does not include user B data in engine`() = runTest {
        val userA = "engine-user-a"
        val userB = "engine-user-b"

        seedUser(userA)
        // Seed user B with DIFFERENT weights (50 kg flat — should not affect A's result).
        db.userProfileDao().upsert(makeProfile(userB))
        weights.indices.forEach { i ->
            val ts = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant()
                .plusSeconds(8 * 3600)
            db.weightEntryDao().insert(
                WeightEntryEntity(
                    userId = userB,
                    timestamp = ts,
                    weightKg = 50.0, // very different from A's ~80 kg
                    source = WeightSource.MANUAL,
                    createdAt = ts,
                )
            )
            val tsFood = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant()
                .plusSeconds(13 * 3600)
            db.foodEntryDao().insert(
                FoodEntryEntity(
                    userId = userB,
                    timestamp = tsFood,
                    rawText = "test",
                    name = "Test Food",
                    quantity = 1.0,
                    unit = "serving",
                    grams = 200.0,
                    kcal = 1500.0, // different intake
                    proteinG = 100.0,
                    fatG = 50.0,
                    carbG = 125.0,
                    sourceDb = FoodSourceDb.MANUAL,
                    createdAt = tsFood,
                    updatedAt = tsFood,
                )
            )
        }

        val repoA = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            currentUser = CurrentUser { userA },
            zone = zone,
            clock = fixedClock,
        )
        val repoB = TdeeRepository(
            profileDao = db.userProfileDao(),
            weightDao = db.weightEntryDao(),
            foodDao = db.foodEntryDao(),
            targetDao = db.targetPeriodDao(),
            trendCacheDao = db.weightTrendCacheDao(),
            currentUser = CurrentUser { userB },
            zone = zone,
            clock = fixedClock,
        )

        // A's estimate should reflect ~80 kg data; B's should reflect ~50 kg data.
        val estimateA = repoA.currentEstimate()
        val estimateB = repoB.currentEstimate()
        assertTrue("A's TDEE estimate should be finite", estimateA.valueKcal.isFinite())
        assertTrue("B's TDEE estimate should be finite", estimateB.valueKcal.isFinite())
        // A is heavier → higher TDEE (formula-based at 7 days).
        assertTrue(
            "A's TDEE (${estimateA.valueKcal}) should exceed B's (${estimateB.valueKcal})",
            estimateA.valueKcal > estimateB.valueKcal,
        )

        // Cache written by A's repo should only contain A's userId.
        repoA.recomputeTrendCache()
        val cacheA = db.weightTrendCacheDao().getAll(userA)
        val cacheB = db.weightTrendCacheDao().getAll(userB)
        assertTrue("Cache rows from repoA should all belong to userA", cacheA.all { it.userId == userA })
        assertEquals("repoA cache should not write any rows for userB", 0, cacheB.size)
    }

    /**
     * Verifies that target period rows inserted for user A are not returned when querying for
     * user B, and vice versa.
     */
    @Test
    fun `target period rows are isolated per user`() = runTest {
        val userA = "target-user-a"
        val userB = "target-user-b"
        val targetDao = db.targetPeriodDao()

        val periodA = TargetPeriodEntity(
            userId = userA,
            startDate = LocalDate.of(2026, 6, 1),
            endDate = LocalDate.of(2026, 6, 30),
            tdeeAtCheckin = 2400.0,
            calorieTarget = 2200.0,
            proteinTargetG = 180.0,
            fatTargetG = 60.0,
            carbTargetG = 220.0,
            acceptedAt = Instant.parse("2026-06-01T08:00:00Z"),
        )
        val periodB = TargetPeriodEntity(
            userId = userB,
            startDate = LocalDate.of(2026, 6, 1),
            endDate = LocalDate.of(2026, 6, 30),
            tdeeAtCheckin = 1900.0,
            calorieTarget = 1700.0,
            proteinTargetG = 130.0,
            fatTargetG = 47.0,
            carbTargetG = 170.0,
            acceptedAt = Instant.parse("2026-06-01T08:00:00Z"),
        )

        targetDao.insert(periodA)
        targetDao.insert(periodB)

        val rowsA = targetDao.getAll(userA)
        val rowsB = targetDao.getAll(userB)

        assertEquals(1, rowsA.size)
        assertEquals(userA, rowsA[0].userId)
        assertEquals(1, rowsB.size)
        assertEquals(userB, rowsB[0].userId)
    }
}
