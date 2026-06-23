package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Tests for [TdeeRepository] extension functions:
 *   - [TdeeRepository.observeProfile]
 *   - [TdeeRepository.saveProfileAndSeedWeight]
 *   - [TdeeRepository.todayConsumed]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryExtensionsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    // Fixed "now" = 2026-06-21T12:00:00Z  →  log-day = 2026-06-21 (dayStartHour=0)
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "ext-test-user"
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
    // observeProfile()
    // -----------------------------------------------------------------------

    @Test
    fun `observeProfile emits null when no profile exists`() = runTest {
        assertNull(repo.observeProfile().first())
    }

    @Test
    fun `observeProfile emits profile after saveProfileAndSeedWeight`() = runTest {
        val profile = makeProfile(userId)
        repo.saveProfileAndSeedWeight(profile, seedWeightKg = 80.0)

        val emitted = repo.observeProfile().first()
        assertNotNull(emitted)
        assertEquals(userId, emitted!!.userId)
        assertEquals(Sex.MALE, emitted.sex)
    }

    // -----------------------------------------------------------------------
    // saveProfileAndSeedWeight()
    // -----------------------------------------------------------------------

    @Test
    fun `saveProfileAndSeedWeight persists profile under current user`() = runTest {
        val profile = makeProfile("wrong-user-id") // userId should be overwritten
        repo.saveProfileAndSeedWeight(profile, seedWeightKg = 78.5)

        val saved = db.userProfileDao().get(userId)
        assertNotNull(saved)
        assertEquals(userId, saved!!.userId)
    }

    @Test
    fun `saveProfileAndSeedWeight creates seed weight entry for current user`() = runTest {
        repo.saveProfileAndSeedWeight(makeProfile(userId), seedWeightKg = 82.3)

        val weights = db.weightEntryDao().getAll(userId)
        assertEquals(1, weights.size)
        assertEquals(82.3, weights[0].weightKg, 0.001)
        assertEquals(WeightSource.MANUAL, weights[0].source)
        assertEquals(userId, weights[0].userId)
    }

    @Test
    fun `saveProfileAndSeedWeight seed weight timestamp equals clock instant`() = runTest {
        repo.saveProfileAndSeedWeight(makeProfile(userId), seedWeightKg = 80.0)

        val weights = db.weightEntryDao().getAll(userId)
        assertEquals(1, weights.size)
        assertEquals(fixedNow, weights[0].timestamp)
    }

    @Test
    fun `saveProfileAndSeedWeight seed weight appears in toWeightSamples`() = runTest {
        repo.saveProfileAndSeedWeight(makeProfile(userId), seedWeightKg = 77.0)

        val samples = db.weightEntryDao().getAll(userId).toWeightSamples()
        assertEquals(1, samples.size)
        assertEquals(77.0, samples[0].kg, 0.001)
    }

    // -----------------------------------------------------------------------
    // todayConsumed()
    // -----------------------------------------------------------------------

    @Test
    fun `todayConsumed returns null when no profile exists`() = runTest {
        assertNull(repo.todayConsumed())
    }

    @Test
    fun `todayConsumed returns null when profile exists but no food entries today`() = runTest {
        db.userProfileDao().upsert(makeProfile(userId))

        assertNull(repo.todayConsumed())
    }

    @Test
    fun `todayConsumed sums today food entries`() = runTest {
        db.userProfileDao().upsert(makeProfile(userId))

        // Two entries on the same log-day as fixedNow (2026-06-21, dayStartHour=0)
        val t1 = Instant.parse("2026-06-21T08:00:00Z")
        val t2 = Instant.parse("2026-06-21T13:00:00Z")
        db.foodEntryDao().insert(makeFoodEntry(userId, t1, kcal = 600.0))
        db.foodEntryDao().insert(makeFoodEntry(userId, t2, kcal = 800.0))

        val consumed = repo.todayConsumed()
        assertNotNull(consumed)
        assertEquals(1400.0, consumed!!.kcal, 0.001)
    }

    @Test
    fun `todayConsumed excludes entries from previous log-days`() = runTest {
        db.userProfileDao().upsert(makeProfile(userId))

        // Entry yesterday — should not count
        val yesterday = Instant.parse("2026-06-20T12:00:00Z")
        db.foodEntryDao().insert(makeFoodEntry(userId, yesterday, kcal = 500.0))

        assertNull(repo.todayConsumed())
    }

    @Test
    fun `todayConsumed excludes soft-deleted entries`() = runTest {
        db.userProfileDao().upsert(makeProfile(userId))

        val t = Instant.parse("2026-06-21T09:00:00Z")
        val id = db.foodEntryDao().insert(makeFoodEntry(userId, t, kcal = 700.0))
        db.foodEntryDao().softDelete(id, fixedNow)

        assertNull(repo.todayConsumed())
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

    private fun makeFoodEntry(uid: String, timestamp: Instant, kcal: Double) = FoodEntryEntity(
        userId = uid,
        timestamp = timestamp,
        rawText = "test",
        name = "Test Food",
        quantity = 1.0,
        unit = "serving",
        grams = 100.0,
        kcal = kcal,
        proteinG = 20.0,
        fatG = 10.0,
        carbG = 50.0,
        sourceDb = FoodSourceDb.MANUAL,
        createdAt = timestamp,
        updatedAt = timestamp,
    )
}
