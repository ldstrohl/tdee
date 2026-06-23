package com.tdee.app.data

import androidx.room.Room
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Tests for [TdeeRepository.exportCsv] (Module 7).
 *
 * Uses an in-memory Room DB, a fake CurrentUser, and a fixed Clock (UTC, dayStartHour = 0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TdeeRepositoryExportTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC

    private val fixedNow = Instant.parse("2026-06-22T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)
    private val today = LocalDate.of(2026, 6, 22)

    private val userId = "export-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val expectedHeader =
        "date,weight_lb,smoothed_weight_lb,calories_kcal,protein_g,fat_g,carb_g,tdee_kcal"

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

    private suspend fun seedProfile() {
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
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
        )
    }

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

    private suspend fun insertFood(
        date: LocalDate,
        kcal: Double,
        proteinG: Double,
        fatG: Double,
        carbG: Double,
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

    private fun rows(csv: String): List<String> =
        csv.trim().lines().drop(1).filter { it.isNotBlank() }

    @Test
    fun `empty DB yields header only`() = runTest {
        seedProfile()
        val csv = repo.exportCsv()
        assertEquals(expectedHeader, csv.trim())
        assertTrue("No data rows expected", rows(csv).isEmpty())
    }

    @Test
    fun `no profile yields header only`() = runTest {
        // No profile seeded.
        val csv = repo.exportCsv()
        assertEquals(expectedHeader, csv.trim())
    }

    @Test
    fun `header is first line and has eight columns`() = runTest {
        seedProfile()
        insertWeight(today, 80.0)
        val csv = repo.exportCsv()
        val header = csv.lines().first()
        assertEquals(expectedHeader, header)
        assertEquals(8, header.split(",").size)
    }

    @Test
    fun `seeded day with weight and food produces expected row values`() = runTest {
        seedProfile()
        // Single day: weight 80 kg → 80 * 2.2046226 = 176.4 lb.
        insertWeight(today, 80.0)
        insertFood(today, kcal = 2200.0, proteinG = 150.0, fatG = 70.0, carbG = 250.0)

        val csv = repo.exportCsv()
        val dataRows = rows(csv)
        assertEquals("One day of data → one row", 1, dataRows.size)

        val cols = dataRows.first().split(",")
        assertEquals("8 columns per row", 8, cols.size)

        assertEquals(today.toString(), cols[0])
        // weight_lb: 80 kg → 176.4 lb (1 dp).
        assertEquals("176.4", cols[1])
        // smoothed_weight_lb is present (non-blank, finite-ish numeric).
        assertTrue("smoothed weight should be present", cols[2].isNotBlank())
        assertNotNull(cols[2].toDoubleOrNull())
        // calories / macros are whole-number sums of the single food entry.
        assertEquals("2200", cols[3])
        assertEquals("150", cols[4])
        assertEquals("70", cols[5])
        assertEquals("250", cols[6])
        // tdee_kcal present and parseable.
        assertNotNull("tdee should be a number", cols[7].toDoubleOrNull())
    }

    @Test
    fun `macro sums combine multiple entries on the same day`() = runTest {
        seedProfile()
        insertWeight(today, 80.0)
        insertFood(today, kcal = 700.0, proteinG = 50.0, fatG = 20.0, carbG = 80.0)
        insertFood(today, kcal = 500.0, proteinG = 30.0, fatG = 10.0, carbG = 60.0)

        val cols = rows(repo.exportCsv()).first().split(",")
        assertEquals("1200", cols[3]) // 700 + 500
        assertEquals("80", cols[4])   // 50 + 30
        assertEquals("30", cols[5])   // 20 + 10
        assertEquals("140", cols[6])  // 80 + 60
    }

    @Test
    fun `day with weight but no food leaves macro and calorie cells blank`() = runTest {
        seedProfile()
        val weightOnlyDay = today.minusDays(2)
        insertWeight(weightOnlyDay, 80.0)
        // A separate day with food so the export has both kinds of rows.
        insertFood(today, kcal = 2000.0, proteinG = 120.0, fatG = 60.0, carbG = 220.0)

        val dataRows = rows(repo.exportCsv())
        val row = dataRows.first { it.startsWith(weightOnlyDay.toString()) }
        val cols = row.split(",")

        assertEquals(8, cols.size)
        // weight present, smoothed present.
        assertTrue(cols[1].isNotBlank())
        assertTrue(cols[2].isNotBlank())
        // calories + macros blank (not "0").
        assertEquals("", cols[3])
        assertEquals("", cols[4])
        assertEquals("", cols[5])
        assertEquals("", cols[6])
        // tdee still present.
        assertTrue(cols[7].isNotBlank())
    }

    @Test
    fun `food-only day leaves weight_lb blank but smoothed and tdee present`() = runTest {
        seedProfile()
        // Weight on one day to anchor the engine.
        insertWeight(today.minusDays(3), 80.0)
        // Food on a different day with no weight measurement.
        val foodDay = today.minusDays(1)
        insertFood(foodDay, kcal = 1800.0, proteinG = 100.0, fatG = 50.0, carbG = 200.0)

        val row = rows(repo.exportCsv()).first { it.startsWith(foodDay.toString()) }
        val cols = row.split(",")
        assertEquals("", cols[1])              // weight_lb blank (no measurement)
        assertTrue(cols[2].isNotBlank())       // smoothed present
        assertEquals("1800", cols[3])          // calories present
        assertTrue(cols[7].isNotBlank())       // tdee present
    }

    @Test
    fun `rows are sorted by date ascending`() = runTest {
        seedProfile()
        insertWeight(today, 80.0)
        insertWeight(today.minusDays(2), 81.0)
        insertFood(today.minusDays(1), kcal = 2000.0, proteinG = 100.0, fatG = 50.0, carbG = 200.0)

        val dates = rows(repo.exportCsv()).map { it.substringBefore(",") }
        assertEquals(dates.sorted(), dates)
    }
}
