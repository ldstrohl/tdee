package com.tdee.app.addfood

import com.tdee.app.data.LlmProvider
import com.tdee.app.data.LlmSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the [LlmFoodParser] adapters using OkHttp [MockWebServer] as the in-process server.
 *
 * Each provider adapter's base URL is injectable, so we point it at the mock server and exercise the
 * success path, terminal AUTH (401), retried RATE_LIMITED (429×3), and BAD_RESPONSE (garbage body).
 * Robolectric provides the real org.json implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmFoodParserTest {

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

    // -----------------------------------------------------------------------
    // Shared fixtures
    // -----------------------------------------------------------------------

    /** Two well-formed items, as the model's inner JSON text. */
    private val itemsJson = """
        {"items":[
          {"name":"Scrambled eggs","displayQuantity":2,"unit":"egg","grams":96,"kcal":143,"proteinG":10,"fatG":11,"carbG":2},
          {"name":"Oatmeal","displayQuantity":1,"unit":"cup","grams":234,"kcal":166,"proteinG":6,"fatG":4,"carbG":28}
        ]}
    """.trimIndent()

    /** The inner model text, JSON-string-escaped for embedding in a provider envelope. */
    private fun quotedItems() = JSONObject.quote(itemsJson)

    private fun assertEggsAndOatmeal(result: ParseResult) {
        assertTrue("expected Success, got $result", result is ParseResult.Success)
        val items = (result as ParseResult.Success).items
        assertEquals(2, items.size)
        val eggs = items[0]
        assertEquals("Scrambled eggs", eggs.name)
        assertEquals(2.0, eggs.displayQuantity, 0.001)
        assertEquals("egg", eggs.unit)
        assertEquals(96.0, eggs.grams)
        assertEquals(143.0, eggs.kcal, 0.001)
        assertEquals(10.0, eggs.proteinG, 0.001)
        assertTrue(eggs.needsConfirmation)
        assertEquals("Oatmeal", items[1].name)
        assertEquals(166.0, items[1].kcal, 0.001)
    }

    private fun assertAuth(adapter: LlmAdapter) {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = runBlocking { adapter.parse("x", "m", "k") }
        assertTrue(result is ParseResult.Failure)
        assertEquals(ParseErrorKind.AUTH, (result as ParseResult.Failure).kind)
        assertEquals("auth is terminal — no retry", 1, server.requestCount)
    }

    private fun assertRetriedRateLimited(adapter: LlmAdapter) {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
        val result = runBlocking { adapter.parse("x", "m", "k") }
        assertTrue(result is ParseResult.Failure)
        assertEquals(ParseErrorKind.RATE_LIMITED, (result as ParseResult.Failure).kind)
        assertEquals("should retry up to 3 times", 3, server.requestCount)
    }

    private fun assertBadResponse(adapter: LlmAdapter) {
        server.enqueue(MockResponse().setBody("not-json-at-all").setResponseCode(200))
        val result = runBlocking { adapter.parse("x", "m", "k") }
        assertTrue(result is ParseResult.Failure)
        assertEquals(ParseErrorKind.BAD_RESPONSE, (result as ParseResult.Failure).kind)
    }

    // -----------------------------------------------------------------------
    // Gemini
    // -----------------------------------------------------------------------

    private fun geminiAdapter() = GeminiAdapter(client, server.url("/").toString())

    @Test
    fun `gemini success maps items and sends api key header`() {
        val envelope = """{"candidates":[{"content":{"parts":[{"text":${quotedItems()}}]}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        val result = runBlocking { geminiAdapter().parse("eggs and oatmeal", "gemini-2.5-flash", "KEY") }
        assertEggsAndOatmeal(result)

        val recorded = server.takeRequest()
        assertEquals("KEY", recorded.getHeader("x-goog-api-key"))
        assertTrue(recorded.path!!.contains("gemini-2.5-flash:generateContent"))
    }

    @Test
    fun `gemini auth failure`() = assertAuth(geminiAdapter())

    @Test
    fun `gemini rate limited retries`() = assertRetriedRateLimited(geminiAdapter())

    @Test
    fun `gemini bad response`() = assertBadResponse(geminiAdapter())

    // -----------------------------------------------------------------------
    // OpenAI
    // -----------------------------------------------------------------------

    private fun openAiAdapter() = OpenAiAdapter(client, server.url("/v1/chat/completions").toString())

    @Test
    fun `openai success maps items and sends bearer header`() {
        val envelope = """{"choices":[{"message":{"content":${quotedItems()}}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        val result = runBlocking { openAiAdapter().parse("eggs and oatmeal", "gpt-4o-mini", "KEY") }
        assertEggsAndOatmeal(result)

        val recorded = server.takeRequest()
        assertEquals("Bearer KEY", recorded.getHeader("Authorization"))
    }

    @Test
    fun `openai auth failure`() = assertAuth(openAiAdapter())

    @Test
    fun `openai rate limited retries`() = assertRetriedRateLimited(openAiAdapter())

    @Test
    fun `openai bad response`() = assertBadResponse(openAiAdapter())

    // -----------------------------------------------------------------------
    // Anthropic
    // -----------------------------------------------------------------------

    private fun anthropicAdapter() = AnthropicAdapter(client, server.url("/v1/messages").toString())

    @Test
    fun `anthropic success maps items and sends version and key headers`() {
        val envelope = """{"content":[{"type":"text","text":${quotedItems()}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        val result = runBlocking { anthropicAdapter().parse("eggs and oatmeal", "claude-haiku-4-5", "KEY") }
        assertEggsAndOatmeal(result)

        val recorded = server.takeRequest()
        assertEquals("KEY", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
    }

    @Test
    fun `anthropic auth failure`() = assertAuth(anthropicAdapter())

    @Test
    fun `anthropic rate limited retries`() = assertRetriedRateLimited(anthropicAdapter())

    @Test
    fun `anthropic bad response`() = assertBadResponse(anthropicAdapter())

    // -----------------------------------------------------------------------
    // LlmFoodParser dispatch
    // -----------------------------------------------------------------------

    @Test
    fun `no key returns NO_KEY failure without a network call`() {
        val settings = fakeSettings(LlmProvider.GEMINI, key = null)
        val parser = LlmFoodParser(settings, client)
        val result = runBlocking { parser.parse("eggs") }
        assertTrue(result is ParseResult.Failure)
        assertEquals(ParseErrorKind.NO_KEY, (result as ParseResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with a key it dispatches to the active provider adapter`() {
        val envelope = """{"candidates":[{"content":{"parts":[{"text":${quotedItems()}}]}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        val settings = fakeSettings(LlmProvider.GEMINI, key = "KEY")
        val parser = LlmFoodParser(settings, client, geminiBaseUrl = server.url("/").toString())
        val result = runBlocking { parser.parse("eggs and oatmeal") }
        assertEggsAndOatmeal(result)
    }

    private fun fakeSettings(p: LlmProvider, key: String?) = object : LlmSettings {
        override val provider = p
        override fun modelFor(provider: LlmProvider) = provider.defaultModel
        override fun keyFor(provider: LlmProvider) = key
    }
}
