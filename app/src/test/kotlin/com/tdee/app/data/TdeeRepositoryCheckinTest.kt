package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import com.tdee.domain.Targets
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests for the Module 8 check-in methods on [TdeeRepository]:
 * activeTargets / checkinDue / proposeCheckin / commitTargets.
 *
 * Setup mirrors [TdeeRepositoryTest]: a male user, 7 days of weight (80→79.3 kg) and matching
 * 2200 kcal/day food, UTC with dayStartHour=0, clock fixed to noon on 2026-06-21 (day after the
 * last seeded day) so today's log-day = 2026-06-21.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryCheckinTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val today = LocalDate.of(2026, 6, 21)

    private val userId = "checkin-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val day0 = LocalDate.of(2026, 6, 13)
    private val weights = listOf(80.0, 79.9, 79.8, 79.6, 79.5, 79.4, 79.3)
    private val dailyKcal = 2200.0

    @Before
    fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        db.userProfileDao().upsert(makeProfile())

        weights.forEachIndexed { i, kg ->
            val ts = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant().plusSeconds(8 * 3600)
            db.weightEntryDao().insert(
                WeightEntryEntity(
                    userId = userId,
                    timestamp = ts,
                    weightKg = kg,
                    source = WeightSource.MANUAL,
                    createdAt = ts,
                )
            )
            val tsFood = day0.plusDays(i.toLong()).atStartOfDay(zone).toInstant().plusSeconds(13 * 3600)
            db.foodEntryDao().insert(
                FoodEntryEntity(
                    userId = userId,
                    timestamp = tsFood,
                    rawText = "test",
                    name = "Test Food",
                    quantity = 1.0,
                    unit = "serving",
                    grams = 500.0,
                    kcal = dailyKcal,
                    proteinG = 150.0,
                    fatG = 61.1,
                    carbG = 263.9,
                    sourceDb = FoodSourceDb.MANUAL,
                    createdAt = tsFood,
                    updatedAt = tsFood,
                )
            )
        }

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
    fun teardown() = db.close()

    private fun makeProfile() = UserProfileEntity(
        userId = userId,
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
    // activeTargets
    // -----------------------------------------------------------------------

    @Test
    fun `activeTargets falls back to proposedTargets when no period exists`() = runTest {
        assertEquals(repo.proposedTargets(), repo.activeTargets())
    }

    @Test
    fun `activeTargets returns the committed targets after commitTargets`() = runTest {
        val t = Targets(calorieTargetKcal = 1900.0, proteinG = 170.0, fatG = 53.0, carbG = 160.0)
        repo.commitTargets(t, tdeeAtCheckinKcal = 2400.0)
        assertEquals(t, repo.activeTargets())
    }

    @Test
    fun `activeTargets returns the latest period by startDate when two exist`() = runTest {
        val older = Targets(2000.0, 150.0, 55.0, 200.0)
        val newer = Targets(1700.0, 160.0, 47.0, 150.0)
        // Older period: backdated startDate.
        db.targetPeriodDao().insert(
            TargetPeriodEntity(
                userId = userId,
                startDate = today.minusDays(10),
                endDate = today.minusDays(3),
                tdeeAtCheckin = 2300.0,
                calorieTarget = older.calorieTargetKcal,
                proteinTargetG = older.proteinG,
                fatTargetG = older.fatG,
                carbTargetG = older.carbG,
                acceptedAt = Instant.parse("2026-06-11T08:00:00Z"),
            )
        )
        // Newer period: startDate = today (commitTargets path).
        repo.commitTargets(newer, tdeeAtCheckinKcal = 2200.0)

        assertEquals(newer, repo.activeTargets())
    }

    // -----------------------------------------------------------------------
    // checkinDue
    // -----------------------------------------------------------------------

    @Test
    fun `checkinDue is true with no period`() = runTest {
        assertTrue(repo.checkinDue())
    }

    @Test
    fun `checkinDue is false right after commitTargets`() = runTest {
        repo.commitTargets(repo.proposedTargets(), tdeeAtCheckinKcal = 2400.0)
        assertFalse(repo.checkinDue())
    }

    @Test
    fun `checkinDue is true again when the latest period is 7 days old`() = runTest {
        // Backdate a period to exactly 7 days before today's log-day → due.
        db.targetPeriodDao().insert(
            TargetPeriodEntity(
                userId = userId,
                startDate = today.minusDays(7),
                endDate = today,
                tdeeAtCheckin = 2400.0,
                calorieTarget = 2000.0,
                proteinTargetG = 150.0,
                fatTargetG = 55.0,
                carbTargetG = 200.0,
                acceptedAt = Instant.parse("2026-06-14T08:00:00Z"),
            )
        )
        assertTrue("period startDate 7 days ago should be due", repo.checkinDue())
    }

    @Test
    fun `checkinDue is false when the latest period is only 6 days old`() = runTest {
        db.targetPeriodDao().insert(
            TargetPeriodEntity(
                userId = userId,
                startDate = today.minusDays(6),
                endDate = today.plusDays(1),
                tdeeAtCheckin = 2400.0,
                calorieTarget = 2000.0,
                proteinTargetG = 150.0,
                fatTargetG = 55.0,
                carbTargetG = 200.0,
                acceptedAt = Instant.parse("2026-06-15T08:00:00Z"),
            )
        )
        assertFalse("period startDate 6 days ago should not be due", repo.checkinDue())
    }

    // -----------------------------------------------------------------------
    // proposeCheckin
    // -----------------------------------------------------------------------

    @Test
    fun `proposeCheckin returns proposed targets, null currentTargets, and computed summary`() =
        runTest {
            val proposal = repo.proposeCheckin()

            assertEquals(
                "proposedTargets should equal live proposedTargets()",
                repo.proposedTargets(),
                proposal.proposedTargets,
            )
            assertNull("currentTargets should be null when no period exists", proposal.currentTargets)
            assertTrue("tdeeKcal should be finite", proposal.tdeeKcal.isFinite())
            // 7 complete days of 2200 kcal seeded; window [today-7..today-1] covers 6 of them
            // (2026-06-14..2026-06-19 are complete; 2026-06-13 falls outside the 7-day window).
            assertNotNull("last7AvgIntakeKcal should be present", proposal.last7AvgIntakeKcal)
            assertEquals(2200.0, proposal.last7AvgIntakeKcal!!, 0.001)
            // Weight trended down ~0.7 kg over the data → negative lb change.
            assertTrue("trendChangeLb should be negative (losing)", proposal.trendChangeLb < 0.0)
        }

    @Test
    fun `proposeCheckin currentTargets is set after a commit`() = runTest {
        val t = Targets(1900.0, 170.0, 53.0, 160.0)
        repo.commitTargets(t, tdeeAtCheckinKcal = 2400.0)

        val proposal = repo.proposeCheckin()
        assertEquals("currentTargets should equal the active period", t, proposal.currentTargets)
    }

    // -----------------------------------------------------------------------
    // commitTargets (manual-edit path)
    // -----------------------------------------------------------------------

    @Test
    fun `commitTargets makes activeTargets return them immediately and flips checkinDue false`() =
        runTest {
            assertTrue("due before any commit", repo.checkinDue())

            val edited = Targets(calorieTargetKcal = 1750.0, proteinG = 180.0, fatG = 50.0, carbG = 140.0)
            repo.commitTargets(edited, tdeeAtCheckinKcal = 2350.0)

            assertEquals("activeTargets reflects the edit immediately", edited, repo.activeTargets())
            assertFalse("checkinDue flips to false after the edit", repo.checkinDue())

            // Period is dated today with a 7-day span and records the tdee snapshot.
            val latest = db.targetPeriodDao().getLatest(userId)!!
            assertEquals(today, latest.startDate)
            assertEquals(today.plusDays(7), latest.endDate)
            assertEquals(2350.0, latest.tdeeAtCheckin, 0.0)
        }
}
