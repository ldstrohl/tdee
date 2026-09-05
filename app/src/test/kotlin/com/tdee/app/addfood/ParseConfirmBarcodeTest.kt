package com.tdee.app.addfood

import androidx.room.Room
import com.tdee.app.data.AppDatabase
import com.tdee.app.data.CurrentUser
import com.tdee.app.data.Macro
import com.tdee.app.data.OpenFoodFactsClient
import com.tdee.app.data.ProductLookupService
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * Unit tests for [ParseConfirmViewModel.lookupBarcode]: scanned products are appended to the item
 * list (not replaced), not-found/failure are surfaced via `parseError`, and gap macros are marked
 * as estimated on the row.
 *
 * Same Robolectric + in-memory Room harness as [ParseConfirmViewModelTest], plus a real
 * [OpenFoodFactsClient] against a [MockWebServer] (mirrors [com.tdee.app.data.OpenFoodFactsClientTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParseConfirmBarcodeTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TdeeRepository
    private lateinit var vm: ParseConfirmViewModel
    private lateinit var server: MockWebServer

    private val zone = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2026-06-21T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, zone)

    private val userId = "parse-confirm-barcode-test-user"
    private val fakeCurrentUser = CurrentUser { userId }

    private val testDispatcher = UnconfinedTestDispatcher()

    /** A [FoodParser] double used only for gap-filling, so the scan tests are independent of the LLM. */
    private class RecordingParser(private val result: ParseResult) : FoodParser {
        var invoked = false
        override suspend fun parse(text: String, imageJpeg: ByteArray?): ParseResult {
            invoked = true
            return result
        }
    }

    private val gapFillParser = RecordingParser(
        ParseResult.Success(
            listOf(
                ParsedFoodItem(
                    name = "LLM guess",
                    displayQuantity = 100.0,
                    unit = "g",
                    grams = 100.0,
                    kcal = 999.0,
                    proteinG = 999.0,
                    fatG = 12.5,
                    carbG = 999.0,
                    needsConfirmation = true,
                ),
            ),
        ),
    )

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)

        server = MockWebServer()
        server.start()

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

        val offClient = OpenFoodFactsClient(OkHttpClient(), server.url("/").toString())
        val lookupService = ProductLookupService(offClient, gapFillParser)
        vm = ParseConfirmViewModel(LocalHeuristicFoodParser(), repo, productLookup = lookupService)
    }

    @After
    fun teardown() {
        testDispatcher.scheduler.advanceUntilIdle()
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private val pringlesJson = """
        {
          "code": "0038000138416",
          "status": 1,
          "product": {
            "code": "0038000138416",
            "product_name": "Original Potato Crisps",
            "brands": "Pringles",
            "serving_quantity": 28,
            "serving_quantity_unit": "g",
            "nutriments": {
              "energy-kcal_serving": 150,
              "fat_serving": 8.96,
              "proteins_serving": 0.98,
              "carbohydrates_serving": 16
            }
          }
        }
    """.trimIndent()

    private val missingFatJson = """
        {
          "code": "2222222222222",
          "status": 1,
          "product": {
            "code": "2222222222222",
            "product_name": "No Fat Data Bar",
            "brands": "TestBrand",
            "nutriments": {
              "energy-kcal_100g": 300,
              "proteins_100g": 10,
              "carbohydrates_100g": 40
            }
          }
        }
    """.trimIndent()

    private val notFoundJson = """
        {"code":"0049000006344","status":0,"status_verbose":"product not found"}
    """.trimIndent()

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * [lookupBarcode][ParseConfirmViewModel.lookupBarcode] goes through [OpenFoodFactsClient]'s
     * real `withContext(Dispatchers.IO)` network call, a genuine suspension the
     * [UnconfinedTestDispatcher] can't fast-forward — so tests must wait for it to actually finish
     * rather than asserting immediately after the call.
     */
    private suspend fun awaitLookup() {
        vm.state.filter { !it.parsing }.first()
    }

    @Test
    fun `found product is appended and saveAll writes it`() = runTest {
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))

        vm.lookupBarcode("0038000138416")
        awaitLookup()

        val items = vm.state.value.items
        assertEquals(1, items.size)
        assertEquals("Pringles Original Potato Crisps", items[0].name)
        assertEquals("150.0", items[0].kcal)
        assertEquals("28.0", items[0].grams)

        vm.saveAll()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(1, entries.size)
        assertEquals("Pringles Original Potato Crisps", entries[0].name)
        assertEquals(150.0, entries[0].kcal, 0.001)
    }

    @Test
    fun `two successive lookups append two items`() = runTest {
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))

        vm.lookupBarcode("0038000138416")
        awaitLookup()
        vm.lookupBarcode("0038000138416")
        awaitLookup()

        assertEquals(2, vm.state.value.items.size)
    }

    @Test
    fun `not-found surfaces an error naming the barcode and leaves items unchanged`() = runTest {
        server.enqueue(MockResponse().setBody(notFoundJson).setResponseCode(200))

        vm.lookupBarcode("0049000006344")
        awaitLookup()

        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.parseError?.contains("0049000006344") == true)
        assertTrue(vm.state.value.parseError?.contains("Photograph the nutrition label") == true)
    }

    @Test
    fun `a network failure surfaces its message`() = runTest {
        // 400 is not retried (only 429/500/503 are), so this resolves on the first attempt.
        server.enqueue(MockResponse().setResponseCode(400))

        vm.lookupBarcode("0038000138416")
        awaitLookup()

        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.parseError != null)
    }

    @Test
    fun `blank barcode does not call the service`() = runTest {
        vm.lookupBarcode("   ")

        assertEquals(0, server.requestCount)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `a trailing dash is stripped before lookup`() = runTest {
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))

        vm.lookupBarcode("0038000138416-")
        awaitLookup()

        val recorded = server.takeRequest()
        assertTrue(
            "expected the stripped digits in the request path, got ${recorded.path}",
            recorded.path?.contains("0038000138416.json") == true,
        )
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun `a barcode with no digits does not call the service`() = runTest {
        vm.lookupBarcode("---")

        assertEquals(0, server.requestCount)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `a lookup with a gap marks that macro as estimated on the row`() = runTest {
        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))

        vm.lookupBarcode("2222222222222")
        awaitLookup()

        assertTrue(gapFillParser.invoked)
        val item = vm.state.value.items.single()
        assertEquals(setOf(Macro.FAT), item.estimatedMacros)
        assertEquals("12.5", item.fatG)
    }

    @Test
    fun `x2 factor on a scanned item doubles the saved kcal`() = runTest {
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))
        vm.lookupBarcode("0038000138416")
        awaitLookup()

        vm.setFactor(0, "2")
        vm.saveAll()
        vm.saved.filter { it }.first()

        val entries = repo.todayFoodEntries()
        assertEquals(1, entries.size)
        assertEquals(300.0, entries[0].kcal, 0.001)
    }
    @Test
    fun `parsing text does not discard an already-scanned item`() = runTest {
        // The text box and the scanner both feed one list. Re-parsing replaces what the parser
        // produced last time, but a scanned row is not the parser's to throw away.
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))
        vm.lookupBarcode("0038000138416")
        vm.state.filter { !it.parsing }.first()
        assertEquals(1, vm.state.value.items.size)

        vm.setText("a coffee")
        vm.parse()
        vm.state.filter { !it.parsing }.first()

        val names = vm.state.value.items.map { it.name }
        assertTrue(
            "scanned item should survive a re-parse, got $names",
            names.any { it.contains("Pringles") },
        )
        assertTrue("parsed item should be present too, got $names", names.size >= 2)
    }

}
