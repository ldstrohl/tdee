package com.tdee.app.data

import com.tdee.app.addfood.FoodParser
import com.tdee.app.addfood.ParseErrorKind
import com.tdee.app.addfood.ParseResult
import com.tdee.app.addfood.ParsedFoodItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ProductLookupService]: LLM gap-filling on top of [OpenFoodFactsClient], where
 * OFF-supplied values must always win over anything the LLM returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductLookupServiceTest {

    private lateinit var server: MockWebServer
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun offClient() = OpenFoodFactsClient(httpClient, server.url("/").toString())

    /** A [FoodParser] double that records whether it was invoked and returns a fixed [result]. */
    private class RecordingParser(private val result: ParseResult) : FoodParser {
        var invoked = false
        var lastText: String? = null

        override suspend fun parse(text: String, imageJpeg: ByteArray?): ParseResult {
            invoked = true
            lastText = text
            return result
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures (same shapes as OpenFoodFactsClientTest)
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

    /** Missing fat only. */
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

    private fun estimatedItem(fat: Double, kcal: Double = 999.0, protein: Double = 999.0) = ParsedFoodItem(
        name = "LLM guess",
        displayQuantity = 100.0,
        unit = "g",
        grams = 100.0,
        kcal = kcal,
        proteinG = protein,
        fatG = fat,
        carbG = 999.0,
        needsConfirmation = true,
    )

    // -----------------------------------------------------------------------
    // Service-level behaviour
    // -----------------------------------------------------------------------

    @Test
    fun `no gaps means the parser is never invoked and the item is unchanged`() {
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))
        val parser = RecordingParser(ParseResult.Success(listOf(estimatedItem(fat = 1.0))))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("0038000138416") }

        assertFalse(parser.invoked)
        assertTrue(result is ProductLookup.Found)
        val found = result as ProductLookup.Found
        assertEquals(8.96, found.item.fatG, 0.001)
        assertTrue(found.gaps.isEmpty())
    }

    @Test
    fun `a fat gap is filled from the LLM estimate`() {
        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))
        val parser = RecordingParser(ParseResult.Success(listOf(estimatedItem(fat = 12.5))))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("2222222222222") }

        assertTrue(parser.invoked)
        val found = result as ProductLookup.Found
        assertEquals(12.5, found.item.fatG, 0.001)
        assertEquals(setOf(Macro.FAT), found.gaps)
    }

    @Test
    fun `the LLM's kcal and protein are ignored even though only fat was a gap`() {
        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))
        val parser = RecordingParser(ParseResult.Success(listOf(estimatedItem(fat = 12.5, kcal = 5000.0, protein = 5000.0))))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("2222222222222") }

        val found = result as ProductLookup.Found
        assertEquals(300.0, found.item.kcal, 0.001)
        assertEquals(10.0, found.item.proteinG, 0.001)
        assertEquals(12.5, found.item.fatG, 0.001)
    }

    @Test
    fun `a NO_KEY parser failure still returns Found with the gap left at zero`() {
        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))
        val parser = RecordingParser(ParseResult.Failure(ParseErrorKind.NO_KEY, "Add an API key in Settings."))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("2222222222222") }

        assertTrue(result is ProductLookup.Found)
        val found = result as ProductLookup.Found
        assertEquals(0.0, found.item.fatG, 0.001)
        assertEquals(setOf(Macro.FAT), found.gaps)
    }

    @Test
    fun `an empty item list from the parser leaves the item unchanged`() {
        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))
        val parser = RecordingParser(ParseResult.Success(emptyList()))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("2222222222222") }

        val found = result as ProductLookup.Found
        assertEquals(0.0, found.item.fatG, 0.001)
    }

    @Test
    fun `a negative or NaN estimate is rejected`() {
        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))
        val parser = RecordingParser(ParseResult.Success(listOf(estimatedItem(fat = Double.NaN))))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("2222222222222") }
        assertEquals(0.0, (result as ProductLookup.Found).item.fatG, 0.001)

        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))
        val negativeParser = RecordingParser(ParseResult.Success(listOf(estimatedItem(fat = -5.0))))
        val negativeResult = runBlocking { ProductLookupService(offClient(), negativeParser).lookup("2222222222222") }
        assertEquals(0.0, (negativeResult as ProductLookup.Found).item.fatG, 0.001)
    }

    @Test
    fun `gaps is preserved in the returned Found after a successful fill`() {
        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))
        val parser = RecordingParser(ParseResult.Success(listOf(estimatedItem(fat = 12.5))))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("2222222222222") }
        assertEquals(setOf(Macro.FAT), (result as ProductLookup.Found).gaps)
    }

    @Test
    fun `OFF NotFound passes through without invoking the parser`() {
        server.enqueue(MockResponse().setBody(notFoundJson).setResponseCode(200))
        val parser = RecordingParser(ParseResult.Success(listOf(estimatedItem(fat = 1.0))))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("0049000006344") }

        assertFalse(parser.invoked)
        assertEquals(ProductLookup.NotFound, result)
    }

    @Test
    fun `OFF Failure passes through without invoking the parser`() {
        server.enqueue(MockResponse().setResponseCode(400))
        val parser = RecordingParser(ParseResult.Success(listOf(estimatedItem(fat = 1.0))))
        val service = ProductLookupService(offClient(), parser)

        val result = runBlocking { service.lookup("0038000138416") }

        assertFalse(parser.invoked)
        assertTrue(result is ProductLookup.Failure)
    }

    // -----------------------------------------------------------------------
    // fillGaps unit tests
    // -----------------------------------------------------------------------

    @Test
    fun `fillGaps only touches fields named in gaps`() {
        val off = ParsedFoodItem(
            name = "Off Item",
            displayQuantity = 1.0,
            unit = "serving",
            grams = 28.0,
            kcal = 150.0,
            proteinG = 1.0,
            fatG = 0.0,
            carbG = 16.0,
            needsConfirmation = true,
        )
        val llm = estimatedItem(fat = 8.96, kcal = 999.0, protein = 999.0)

        val filled = off.fillGaps(llm, setOf(Macro.FAT))

        assertEquals(8.96, filled.fatG, 0.001)
        assertEquals(150.0, filled.kcal, 0.001)
        assertEquals(1.0, filled.proteinG, 0.001)
        assertEquals(16.0, filled.carbG, 0.001)
        assertEquals("Off Item", filled.name)
        assertEquals(28.0, filled.grams)
        assertEquals("serving", filled.unit)
        assertEquals(1.0, filled.displayQuantity, 0.001)
    }
}
