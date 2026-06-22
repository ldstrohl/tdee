package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.SampleQuality
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDataLayerTest {

    private lateinit var db: AppDatabase
    private lateinit var weightDao: WeightEntryDao
    private lateinit var foodDao: FoodEntryDao
    private lateinit var profileDao: UserProfileDao
    private lateinit var trendDao: WeightTrendCacheDao

    private val testUserId = "test-user-id"

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        weightDao = db.weightEntryDao()
        foodDao = db.foodEntryDao()
        profileDao = db.userProfileDao()
        trendDao = db.weightTrendCacheDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // 1. Day-boundary bucketing — dayStartHour = 0
    // -----------------------------------------------------------------------

    @Test
    fun `food entries same calendar day with dayStartHour 0 are summed into one DailyIntake`() = runTest {
        val zone = ZoneOffset.UTC
        val dayStartHour = 0

        // Two entries on 2024-01-15 UTC
        val entry1 = makeFoodEntry(
            timestamp = Instant.parse("2024-01-15T08:00:00Z"),
            kcal = 500.0,
        )
        val entry2 = makeFoodEntry(
            timestamp = Instant.parse("2024-01-15T18:00:00Z"),
            kcal = 300.0,
        )
        foodDao.insert(entry1)
        foodDao.insert(entry2)

        val intakes = foodDao.getActive(testUserId).toDailyIntake(zone, dayStartHour)

        assertEquals(1, intakes.size)
        assertEquals(LocalDate.of(2024, 1, 15), intakes[0].date)
        assertEquals(800.0, intakes[0].kcal, 0.001)
        assertTrue(intakes[0].complete)
    }

    @Test
    fun `food entries on different calendar days with dayStartHour 0 produce separate DailyIntakes`() = runTest {
        val zone = ZoneOffset.UTC
        val dayStartHour = 0

        foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-15T08:00:00Z"), kcal = 500.0))
        foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-16T08:00:00Z"), kcal = 400.0))

        val intakes = foodDao.getActive(testUserId).toDailyIntake(zone, dayStartHour)

        assertEquals(2, intakes.size)
        assertEquals(LocalDate.of(2024, 1, 15), intakes[0].date)
        assertEquals(LocalDate.of(2024, 1, 16), intakes[1].date)
    }

    // -----------------------------------------------------------------------
    // 1b. Day-boundary bucketing — custom dayStartHour = 4
    // -----------------------------------------------------------------------

    @Test
    fun `entry before custom day boundary belongs to previous log-day`() = runTest {
        val zone = ZoneOffset.UTC
        val dayStartHour = 4

        // 2024-01-15T03:00Z is before the 04:00 boundary, so it belongs to 2024-01-14
        foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-15T03:00:00Z"), kcal = 200.0))
        // 2024-01-15T05:00Z is after the boundary, so it belongs to 2024-01-15
        foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-15T05:00:00Z"), kcal = 600.0))

        val intakes = foodDao.getActive(testUserId).toDailyIntake(zone, dayStartHour)

        assertEquals(2, intakes.size)
        // First entry → log-day 2024-01-14
        assertEquals(LocalDate.of(2024, 1, 14), intakes[0].date)
        assertEquals(200.0, intakes[0].kcal, 0.001)
        // Second entry → log-day 2024-01-15
        assertEquals(LocalDate.of(2024, 1, 15), intakes[1].date)
        assertEquals(600.0, intakes[1].kcal, 0.001)
    }

    @Test
    fun `entries near custom boundary on same log-day are summed`() = runTest {
        val zone = ZoneOffset.UTC
        val dayStartHour = 4

        // Both entries fall in the log-day of 2024-01-15 (between 04:00 on 15th and 04:00 on 16th)
        foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-15T04:30:00Z"), kcal = 350.0))
        foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-16T03:59:00Z"), kcal = 250.0))

        val intakes = foodDao.getActive(testUserId).toDailyIntake(zone, dayStartHour)

        assertEquals(1, intakes.size)
        assertEquals(LocalDate.of(2024, 1, 15), intakes[0].date)
        assertEquals(600.0, intakes[0].kcal, 0.001)
    }

    // -----------------------------------------------------------------------
    // 2. Completeness
    // -----------------------------------------------------------------------

    @Test
    fun `days with entries produce complete=true DailyIntake`() = runTest {
        foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-15T12:00:00Z"), kcal = 500.0))

        val intakes = foodDao.getActive(testUserId).toDailyIntake(ZoneOffset.UTC, 0)

        assertEquals(1, intakes.size)
        assertTrue(intakes[0].complete)
    }

    @Test
    fun `days with no entries produce no DailyIntake rows`() = runTest {
        // Only one entry on Jan 15; Jan 16 has no entries
        foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-15T12:00:00Z"), kcal = 500.0))

        val intakes = foodDao.getActive(testUserId).toDailyIntake(ZoneOffset.UTC, 0)

        val dates = intakes.map { it.date }
        assertTrue(LocalDate.of(2024, 1, 15) in dates)
        assertTrue(LocalDate.of(2024, 1, 16) !in dates)
    }

    @Test
    fun `soft-deleted entries are excluded from DailyIntake`() = runTest {
        val id = foodDao.insert(makeFoodEntry(timestamp = Instant.parse("2024-01-15T12:00:00Z"), kcal = 500.0))
        foodDao.softDelete(id, Instant.now())

        val intakes = foodDao.getActive(testUserId).toDailyIntake(ZoneOffset.UTC, 0)

        assertTrue(intakes.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 3. Weight mapping
    // -----------------------------------------------------------------------

    @Test
    fun `HEALTH_CONNECT source maps to DEVICE quality`() = runTest {
        weightDao.insert(makeWeightEntry(source = WeightSource.HEALTH_CONNECT, weightKg = 80.0))

        val samples = weightDao.getAll(testUserId).toWeightSamples()

        assertEquals(1, samples.size)
        assertEquals(SampleQuality.DEVICE, samples[0].quality)
        assertEquals(80.0, samples[0].kg, 0.001)
    }

    @Test
    fun `MANUAL source maps to MANUAL quality`() = runTest {
        weightDao.insert(makeWeightEntry(source = WeightSource.MANUAL, weightKg = 75.0))

        val samples = weightDao.getAll(testUserId).toWeightSamples()

        assertEquals(1, samples.size)
        assertEquals(SampleQuality.MANUAL, samples[0].quality)
    }

    @Test
    fun `multiple weigh-ins on same day are both present in toWeightSamples (no aggregation)`() = runTest {
        val t1 = Instant.parse("2024-01-15T07:00:00Z")
        val t2 = Instant.parse("2024-01-15T20:00:00Z")
        weightDao.insert(makeWeightEntry(timestamp = t1, weightKg = 80.0))
        weightDao.insert(makeWeightEntry(timestamp = t2, weightKg = 80.5))

        val samples = weightDao.getAll(testUserId).toWeightSamples()

        assertEquals(2, samples.size)
        assertTrue(samples.any { it.t == t1 })
        assertTrue(samples.any { it.t == t2 })
    }

    // -----------------------------------------------------------------------
    // 4. HealthConnect-uid dedup (per-user)
    // -----------------------------------------------------------------------

    @Test
    fun `inserting two entries with same healthConnectUid for same user yields one row`() = runTest {
        val e1 = makeWeightEntry(weightKg = 80.0, healthConnectUid = "hc-uid-001")
        val e2 = makeWeightEntry(weightKg = 81.0, healthConnectUid = "hc-uid-001")

        weightDao.insertIgnoreDuplicate(e1)
        weightDao.insertIgnoreDuplicate(e2) // should be ignored

        val all = weightDao.getAll(testUserId)
        assertEquals(1, all.size)
        assertEquals(80.0, all[0].weightKg, 0.001)
    }

    @Test
    fun `same healthConnectUid for different users is not deduped`() = runTest {
        val userA = "user-a"
        val userB = "user-b"
        val e1 = makeWeightEntry(weightKg = 80.0, healthConnectUid = "hc-uid-shared", userId = userA)
        val e2 = makeWeightEntry(weightKg = 81.0, healthConnectUid = "hc-uid-shared", userId = userB)

        weightDao.insertIgnoreDuplicate(e1)
        weightDao.insertIgnoreDuplicate(e2)

        // Each user sees their own row; the unique constraint is per (userId, healthConnectUid).
        assertEquals(1, weightDao.getAll(userA).size)
        assertEquals(1, weightDao.getAll(userB).size)
    }

    @Test
    fun `entries with null healthConnectUid are not deduped against each other`() = runTest {
        val e1 = makeWeightEntry(weightKg = 80.0, healthConnectUid = null)
        val e2 = makeWeightEntry(weightKg = 81.0, healthConnectUid = null)

        weightDao.insertIgnoreDuplicate(e1)
        weightDao.insertIgnoreDuplicate(e2)

        val all = weightDao.getAll(testUserId)
        assertEquals(2, all.size)
    }

    // -----------------------------------------------------------------------
    // 5. TypeConverter round-trips
    // -----------------------------------------------------------------------

    @Test
    fun `Instant round-trips through WeightEntryEntity`() = runTest {
        val ts = Instant.parse("2024-06-15T14:30:00Z")
        weightDao.insert(makeWeightEntry(timestamp = ts, weightKg = 78.0))

        val loaded = weightDao.getAll(testUserId).first()
        assertEquals(ts, loaded.timestamp)
    }

    @Test
    fun `LocalDate round-trips through WeightTrendCacheEntity`() = runTest {
        val date = LocalDate.of(2024, 3, 22)
        val entity = WeightTrendCacheEntity(
            userId = testUserId,
            date = date,
            emaKg = 79.5,
            tdeeEstimate = 2200.0,
            tdeeMethod = TdeeMethodDb.BLEND,
            uncertaintyKcal = 150.0,
            calibrating = false,
        )
        trendDao.upsert(entity)

        val loaded = trendDao.getByDate(testUserId, date)!!
        assertEquals(date, loaded.date)
        assertEquals(TdeeMethodDb.BLEND, loaded.tdeeMethod)
        assertEquals(79.5, loaded.emaKg, 0.001)
    }

    @Test
    fun `enum fields round-trip as String names`() = runTest {
        val ts = Instant.parse("2024-01-10T10:00:00Z")
        val entry = makeWeightEntry(
            timestamp = ts,
            weightKg = 70.0,
            source = WeightSource.HEALTH_CONNECT,
            healthConnectUid = "enum-test-uid",
        )
        weightDao.insert(entry)

        val loaded = weightDao.getAll(testUserId).first()
        assertEquals(WeightSource.HEALTH_CONNECT, loaded.source)
    }

    @Test
    fun `FoodEntryEntity enum and Instant round-trip`() = runTest {
        val ts = Instant.parse("2024-02-20T09:15:00Z")
        val entry = makeFoodEntry(timestamp = ts, kcal = 450.0, sourceDb = FoodSourceDb.USDA)
        foodDao.insert(entry)

        val loaded = foodDao.getAll(testUserId).first()
        assertEquals(ts, loaded.timestamp)
        assertEquals(FoodSourceDb.USDA, loaded.sourceDb)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeWeightEntry(
        timestamp: Instant = Instant.parse("2024-01-15T08:00:00Z"),
        weightKg: Double = 80.0,
        source: WeightSource = WeightSource.MANUAL,
        healthConnectUid: String? = null,
        userId: String = testUserId,
    ): WeightEntryEntity = WeightEntryEntity(
        userId = userId,
        timestamp = timestamp,
        weightKg = weightKg,
        source = source,
        healthConnectUid = healthConnectUid,
        createdAt = Instant.now(),
    )

    private fun makeFoodEntry(
        timestamp: Instant = Instant.parse("2024-01-15T12:00:00Z"),
        kcal: Double = 500.0,
        sourceDb: FoodSourceDb = FoodSourceDb.MANUAL,
        userId: String = testUserId,
    ): FoodEntryEntity = FoodEntryEntity(
        userId = userId,
        timestamp = timestamp,
        rawText = "test food",
        name = "Test Food",
        quantity = 1.0,
        unit = "serving",
        grams = 100.0,
        kcal = kcal,
        proteinG = 20.0,
        fatG = 10.0,
        carbG = 50.0,
        sourceDb = sourceDb,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
