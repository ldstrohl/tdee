package com.tdee.app.data

import com.tdee.app.addfood.ParseErrorKind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OpenFoodFactsClient] using OkHttp [MockWebServer] as the in-process server.
 *
 * Fixtures are trimmed copies of real Open Food Facts API v2 responses (Pringles: per-serving
 * data present; Nutella: per-100g only, and a brand-name that duplicates the product name;
 * Coca-Cola: a 330 ml serving, i.e. serving_quantity_unit is a volume, not a mass).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenFoodFactsClientTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun offClient() = OpenFoodFactsClient(client, server.url("/").toString())

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private val pringlesJson = """
        {
          "code": "0038000138416",
          "status": 1,
          "status_verbose": "product found",
          "product": {
            "code": "0038000138416",
            "product_name": "Original Potato Crisps",
            "brands": "Pringles",
            "quantity": "5.2 oz",
            "serving_size": "1 serving (28 g)",
            "serving_quantity": 28,
            "serving_quantity_unit": "g",
            "nutriments": {
              "energy-kcal_100g": 536,
              "energy-kcal_serving": 150,
              "energy-kj_100g": 2244.5,
              "energy-kj_serving": 628,
              "fat_100g": 32,
              "fat_serving": 8.96,
              "proteins_100g": 3.5,
              "proteins_serving": 0.98,
              "carbohydrates_100g": 57,
              "carbohydrates_serving": 16
            }
          }
        }
    """.trimIndent()

    private val nutellaJson = """
        {
          "code": "3017620422003",
          "status": 1,
          "status_verbose": "product found",
          "product": {
            "code": "3017620422003",
            "product_name": "Nutella",
            "brands": "Nutella, Ferrero, Yum yum",
            "quantity": "",
            "nutriments": {
              "energy-kcal_100g": 539,
              "energy-kj_100g": 2252,
              "fat_100g": 30.9,
              "proteins_100g": 6.3,
              "carbohydrates_100g": 57.5
            }
          }
        }
    """.trimIndent()

    private val colaJson = """
        {
          "code": "5449000000996",
          "status": 1,
          "status_verbose": "product found",
          "product": {
            "code": "5449000000996",
            "product_name": "Coca-Cola",
            "brands": "Coca-Cola",
            "quantity": "330 ml",
            "serving_size": "1 portion (330 ml)",
            "serving_quantity": 330,
            "serving_quantity_unit": "ml",
            "nutriments": {
              "energy-kcal_100g": 42,
              "energy-kcal_serving": 139,
              "fat_100g": 0,
              "fat_serving": 0,
              "proteins_100g": 0,
              "proteins_serving": 0,
              "carbohydrates_100g": 10.6,
              "carbohydrates_serving": 35
            }
          }
        }
    """.trimIndent()

    private val notFoundJson = """
        {"code":"0049000006344","status":0,"status_verbose":"product not found"}
    """.trimIndent()

    /** Hand-built: EU-style product with only kJ energy, no direct kcal field, per-100g only. */
    private val kjOnlyJson = """
        {
          "code": "1111111111111",
          "status": 1,
          "status_verbose": "product found",
          "product": {
            "code": "1111111111111",
            "product_name": "KJ Only Biscuit",
            "brands": "TestBrand",
            "nutriments": {
              "energy_100g": 1673,
              "fat_100g": 20,
              "proteins_100g": 5,
              "carbohydrates_100g": 60
            }
          }
        }
    """.trimIndent()

    /** Hand-built: per-100g product missing the fat macro entirely. */
    private val missingFatJson = """
        {
          "code": "2222222222222",
          "status": 1,
          "status_verbose": "product found",
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

    /** Hand-built: no kcal-derivable energy field anywhere → unusable. */
    private val kcalMissingJson = """
        {
          "code": "3333333333333",
          "status": 1,
          "status_verbose": "product found",
          "product": {
            "code": "3333333333333",
            "product_name": "No Energy Data",
            "brands": "TestBrand",
            "nutriments": {
              "fat_100g": 5,
              "proteins_100g": 5,
              "carbohydrates_100g": 5
            }
          }
        }
    """.trimIndent()

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `pringles fixture maps per-serving values`() {
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))
        val result = runBlocking { offClient().lookup("0038000138416") }

        assertTrue("expected Found, got $result", result is ProductLookup.Found)
        val found = result as ProductLookup.Found
        assertEquals("Pringles Original Potato Crisps", found.item.name)
        assertEquals(150.0, found.item.kcal, 0.001)
        assertEquals(8.96, found.item.fatG, 0.001)
        assertEquals(0.98, found.item.proteinG, 0.001)
        assertEquals(16.0, found.item.carbG, 0.001)
        assertEquals(28.0, found.item.grams)
        assertEquals(1.0, found.item.displayQuantity, 0.001)
        assertEquals("serving", found.item.unit)
        assertTrue(found.item.needsConfirmation)
        assertTrue("expected no gaps, got ${found.gaps}", found.gaps.isEmpty())
    }

    @Test
    fun `nutella fixture uses per-100g branch and does not duplicate the brand name`() {
        server.enqueue(MockResponse().setBody(nutellaJson).setResponseCode(200))
        val result = runBlocking { offClient().lookup("3017620422003") }

        assertTrue("expected Found, got $result", result is ProductLookup.Found)
        val found = result as ProductLookup.Found
        assertEquals("Nutella", found.item.name)
        assertEquals(100.0, found.item.grams)
        assertEquals(100.0, found.item.displayQuantity, 0.001)
        assertEquals("g", found.item.unit)
        assertEquals(539.0, found.item.kcal, 0.001)
    }

    @Test
    fun `cola fixture uses per-serving kcal but leaves grams null because the serving unit is ml`() {
        server.enqueue(MockResponse().setBody(colaJson).setResponseCode(200))
        val result = runBlocking { offClient().lookup("5449000000996") }

        assertTrue("expected Found, got $result", result is ProductLookup.Found)
        val found = result as ProductLookup.Found
        assertEquals(139.0, found.item.kcal, 0.001)
        assertNull(found.item.grams)
        assertEquals("serving", found.item.unit)
    }

    @Test
    fun `kJ-only payload converts energy to kcal`() {
        server.enqueue(MockResponse().setBody(kjOnlyJson).setResponseCode(200))
        val result = runBlocking { offClient().lookup("1111111111111") }

        assertTrue("expected Found, got $result", result is ProductLookup.Found)
        val found = result as ProductLookup.Found
        assertEquals(1673.0 / 4.184, found.item.kcal, 0.001)
    }

    @Test
    fun `missing macro is reported as a gap and left at zero`() {
        server.enqueue(MockResponse().setBody(missingFatJson).setResponseCode(200))
        val result = runBlocking { offClient().lookup("2222222222222") }

        assertTrue("expected Found, got $result", result is ProductLookup.Found)
        val found = result as ProductLookup.Found
        assertEquals(setOf(Macro.FAT), found.gaps)
        assertEquals(0.0, found.item.fatG, 0.001)
        assertEquals(10.0, found.item.proteinG, 0.001)
        assertEquals(40.0, found.item.carbG, 0.001)
    }

    @Test
    fun `status zero fixture returns NotFound`() {
        server.enqueue(MockResponse().setBody(notFoundJson).setResponseCode(200))
        val result = runBlocking { offClient().lookup("0049000006344") }
        assertEquals(ProductLookup.NotFound, result)
    }

    @Test
    fun `payload with no usable kcal returns NotFound`() {
        server.enqueue(MockResponse().setBody(kcalMissingJson).setResponseCode(200))
        val result = runBlocking { offClient().lookup("3333333333333") }
        assertEquals(ProductLookup.NotFound, result)
    }

    @Test
    fun `HTTP 404 returns NotFound`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = runBlocking { offClient().lookup("0000000000000") }
        assertEquals(ProductLookup.NotFound, result)
    }

    @Test
    fun `a non-404 client error is a failure, not a missing product`() {
        // Only a 404 means "OFF has no such barcode". Anything else is a real fault, and reporting
        // it as NotFound would tell the user a product they can see on the shelf is not in the
        // database, sending them to re-enter it by hand.
        server.enqueue(MockResponse().setResponseCode(400))
        val result = runBlocking { offClient().lookup("0038000138416") }
        assertTrue("expected Failure, got $result", result is ProductLookup.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `three consecutive 500s produce a SERVER failure and exhaust all retries`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        val result = runBlocking { offClient().lookup("0038000138416") }
        assertTrue("expected Failure, got $result", result is ProductLookup.Failure)
        assertEquals(ParseErrorKind.SERVER, (result as ProductLookup.Failure).kind)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a network failure names Open Food Facts, not meal parsing`() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val result = runBlocking { offClient().lookup("0038000138416") }

        assertTrue("expected Failure, got $result", result is ProductLookup.Failure)
        val failure = result as ProductLookup.Failure
        assertEquals(ParseErrorKind.NETWORK, failure.kind)
        assertEquals("Couldn't reach Open Food Facts — try again.", failure.message)
    }

    @Test
    fun `sends a User-Agent header with no email address`() {
        server.enqueue(MockResponse().setBody(pringlesJson).setResponseCode(200))
        runBlocking { offClient().lookup("0038000138416") }

        val recorded = server.takeRequest()
        val userAgent = recorded.getHeader("User-Agent")
        assertTrue("expected a User-Agent header", !userAgent.isNullOrBlank())
        assertFalse("User-Agent must not contain an email address", userAgent!!.contains("@"))
    }
}
