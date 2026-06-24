package com.tdee.app.addfood

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [WorkerFoodParser] using OkHttp [MockWebServer] as the in-process server.
 *
 * Robolectric provides the real org.json implementation for JSON parsing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkerFoodParserTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // (a) 200 response with two items → correctly mapped ParsedFoodItem list
    @Test
    fun `200 response with two items maps fields correctly`() = runBlocking {
        val responseBody = """
            {
              "items": [
                {
                  "name": "Scrambled eggs",
                  "query": "scrambled eggs",
                  "displayQuantity": 2.0,
                  "unit": "egg",
                  "grams": 96.0,
                  "kcal": 143.0,
                  "proteinG": 10.0,
                  "fatG": 11.0,
                  "carbG": 2.0,
                  "needsConfirmation": true
                },
                {
                  "name": "Oatmeal",
                  "query": "oatmeal",
                  "displayQuantity": 1.0,
                  "unit": "cup",
                  "grams": 234.0,
                  "kcal": 166.0,
                  "proteinG": 6.0,
                  "fatG": 4.0,
                  "carbG": 28.0,
                  "needsConfirmation": true
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val parser = WorkerFoodParser(server.url("/parse").toString())
        val items = parser.parse("scrambled eggs and oatmeal")

        assertEquals(2, items.size)

        val eggs = items[0]
        assertEquals("Scrambled eggs", eggs.name)
        assertEquals(2.0, eggs.displayQuantity, 0.001)
        assertEquals("egg", eggs.unit)
        assertEquals(96.0, eggs.grams)
        assertEquals(143.0, eggs.kcal, 0.001)
        assertEquals(10.0, eggs.proteinG, 0.001)
        assertEquals(11.0, eggs.fatG, 0.001)
        assertEquals(2.0, eggs.carbG, 0.001)
        assertTrue(eggs.needsConfirmation)

        val oats = items[1]
        assertEquals("Oatmeal", oats.name)
        assertEquals(234.0, oats.grams)
        assertEquals(166.0, oats.kcal, 0.001)
    }

    // (b) 500 response → emptyList
    @Test
    fun `500 response returns empty list`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val parser = WorkerFoodParser(server.url("/parse").toString())
        val items = parser.parse("anything")

        assertTrue(items.isEmpty())
    }

    // (c) 200 with malformed body → emptyList
    @Test
    fun `malformed JSON body returns empty list`() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json-at-all").setResponseCode(200))

        val parser = WorkerFoodParser(server.url("/parse").toString())
        val items = parser.parse("anything")

        assertTrue(items.isEmpty())
    }

    // (d) Authorization header present when secret provided, absent when not
    @Test
    fun `Authorization header sent when secret is provided`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[]}""").setResponseCode(200))

        val parser = WorkerFoodParser(server.url("/parse").toString(), sharedSecret = "my-secret")
        parser.parse("test")

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret", recorded.getHeader("Authorization"))
    }

    @Test
    fun `no Authorization header when secret is null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[]}""").setResponseCode(200))

        val parser = WorkerFoodParser(server.url("/parse").toString(), sharedSecret = null)
        parser.parse("test")

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    // Extra: grams is null when absent from the response
    @Test
    fun `grams is null when absent from item`() = runBlocking {
        val responseBody = """
            {
              "items": [
                {
                  "name": "Mystery food",
                  "displayQuantity": 1.0,
                  "unit": "serving",
                  "kcal": 100.0,
                  "proteinG": 5.0,
                  "fatG": 3.0,
                  "carbG": 12.0,
                  "needsConfirmation": true
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val parser = WorkerFoodParser(server.url("/parse").toString())
        val items = parser.parse("mystery food")

        assertEquals(1, items.size)
        assertNull(items[0].grams)
    }
}
