package com.tdee.app.addfood

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.TdeeRepository
import com.tdee.app.data.UserProfileEntity
import com.tdee.app.data.WeightEntryEntity
import com.tdee.app.data.WeightSource
import com.tdee.domain.ActivityLevel
import com.tdee.domain.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
 * Unit tests for [ParseConfirmViewModel.parseLabel]: a nutrition-label photo is transcribed and
 * appended to the item list (not replaced), an empty-items success is treated as "no legible
 * panel" rather than a failure, and label rows are marked [EditableFoodItem.fromScan] so they
 * survive a later [ParseConfirmViewModel.parse] the same way a scanned barcode row does.
 *
 * Same Robolectric + in-memory Room harness as [ParseConfirmViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParseConfirmLabelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "parse-confirm-label-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    /** A [FoodParser] double that records the args it was called with and returns a fixed result. */
    private class RecordingParser(private var result: ParseResult) : FoodParser {
        var lastText: String? = null
        var lastImageJpeg: ByteArray? = null

        override suspend fun parse(text: String, imageJpeg: ByteArray?): ParseResult {
            lastText = text
            lastImageJpeg = imageJpeg
            return result
        }

        fun nextResult(r: ParseResult) {
            result = r
        }
    }

    private lateinit var parser: RecordingParser
    private lateinit var vm: ParseConfirmViewModel

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        val now = fixedNow
        db.userProfileDao().upsert(
            UserProfileEntity(
                userId = userId,
                sex = Sex.MALE,
                birthYear = 1990,
                heightCm = 175.0,
                activityLevel = ActivityLevel.MODERATE,
                goalRateKgPerWeek = 0.0,
                goalWeightKg = null,
                proteinGPerKg = 2.0,
                fatPctOfCalories = 0.25,
                dayStartHour = 0,
                smoothingWindowDays = 14,
                tdeeWindowDays = 14,
                createdAt = now,
                updatedAt = now,
            )
        )
        db.weightEntryDao().insert(
            WeightEntryEntity(
                userId = userId,
                timestamp = now,
                weightKg = 80.0,
                source = WeightSource.MANUAL,
                createdAt = now,
            )
        )

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
            ioDispatcher = testDispatcher,
        )

        parser = RecordingParser(ParseResult.Success(emptyList()))
        vm = ParseConfirmViewModel(parser, repo)
    }

    @After
    fun teardown() {
        testDispatcher.scheduler.advanceUntilIdle()
        db.close()
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private val labelItem = ParsedFoodItem(
        name = "Protein Bar",
        displayQuantity = 1.0,
        unit = "bar",
        grams = 60.0,
        kcal = 210.0,
        proteinG = 20.0,
        fatG = 7.0,
        carbG = 18.0,
        needsConfirmation = true,
    )

    private suspend fun awaitParse() {
        vm.state.filter { !it.parsing }.first()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `a successful label parse appends an item with fromScan set and clears the error`() = runTest {
        parser.nextResult(ParseResult.Success(listOf(labelItem)))

        vm.parseLabel(byteArrayOf(1, 2, 3))
        awaitParse()

        val items = vm.state.value.items
        assertEquals(1, items.size)
        assertEquals("Protein Bar", items[0].name)
        assertTrue(items[0].fromScan)
        assertNull(vm.state.value.parseError)
    }

    @Test
    fun `the bytes handed to parseLabel reach FoodParser unchanged and text is blank`() = runTest {
        val bytes = byteArrayOf(9, 8, 7, 6)
        parser.nextResult(ParseResult.Success(listOf(labelItem)))

        vm.parseLabel(bytes)
        awaitParse()

        assertEquals("", parser.lastText)
        assertArrayEquals(bytes, parser.lastImageJpeg)
    }

    @Test
    fun `an empty-items success sets the no-panel message and leaves existing items untouched`() = runTest {
        parser.nextResult(ParseResult.Success(listOf(labelItem)))
        vm.parseLabel(byteArrayOf(1))
        awaitParse()
        val before = vm.state.value.items

        parser.nextResult(ParseResult.Success(emptyList()))
        vm.parseLabel(byteArrayOf(2))
        awaitParse()

        assertEquals(before, vm.state.value.items)
        assertTrue(vm.state.value.parseError?.contains("nutrition panel") == true)
    }

    @Test
    fun `a Failure result surfaces its message and leaves existing items untouched`() = runTest {
        parser.nextResult(ParseResult.Success(listOf(labelItem)))
        vm.parseLabel(byteArrayOf(1))
        awaitParse()
        val before = vm.state.value.items

        parser.nextResult(ParseResult.Failure(ParseErrorKind.NETWORK, "No connection."))
        vm.parseLabel(byteArrayOf(2))
        awaitParse()

        assertEquals(before, vm.state.value.items)
        assertEquals("No connection.", vm.state.value.parseError)
    }

    @Test
    fun `a label row survives a subsequent text parse alongside the newly parsed rows`() = runTest {
        parser.nextResult(ParseResult.Success(listOf(labelItem)))
        vm.parseLabel(byteArrayOf(1))
        awaitParse()
        assertEquals(1, vm.state.value.items.size)

        parser.nextResult(
            ParseResult.Success(
                listOf(labelItem.copy(name = "Coffee")),
            ),
        )
        vm.setText("a coffee")
        vm.parse()
        awaitParse()

        val names = vm.state.value.items.map { it.name }
        assertTrue("label item should survive, got $names", names.any { it == "Protein Bar" })
        assertTrue("parsed item should be present too, got $names", names.any { it == "Coffee" })
        assertEquals(2, names.size)
    }

    @Test
    fun `mealName already set by a previous parse is not overwritten by a label parse`() = runTest {
        parser.nextResult(ParseResult.Success(listOf(labelItem), mealName = "Breakfast"))
        vm.setText("eggs")
        vm.parse()
        awaitParse()
        assertEquals("Breakfast", vm.state.value.mealName)

        parser.nextResult(ParseResult.Success(listOf(labelItem), mealName = "Snack"))
        vm.parseLabel(byteArrayOf(1))
        awaitParse()

        assertEquals("Breakfast", vm.state.value.mealName)
    }

    @Test
    fun `a label item saves through saveAll with its factor applied`() = runTest {
        parser.nextResult(ParseResult.Success(listOf(labelItem)))
        vm.parseLabel(byteArrayOf(1))
        awaitParse()

        vm.setFactor(0, "2")
        vm.saveAll()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(1, entries.size)
        assertEquals(420.0, entries[0].kcal, 0.001)
    }
}
