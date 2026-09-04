package com.tdee.app.addfood

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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
 * Unit tests for the label-photo path added to the [LlmFoodParser] adapters: the request shape when
 * an image is supplied vs. omitted, and that a label response parses through the existing item
 * extraction. Mirrors the setup in [LlmFoodParserTest] (MockWebServer; Robolectric for real org.json
 * and android.util.Base64).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelImageParserTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val imageBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val expectedB64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val labelItemJson = """
        {"mealName":"Acme Granola Bar","items":[
          {"name":"Acme Granola Bar","displayQuantity":1,"unit":"serving","grams":42,"kcal":190,"proteinG":3,"fatG":7,"carbG":29}
        ]}
    """.trimIndent()

    private fun quotedLabelItem() = JSONObject.quote(labelItemJson)

    // -----------------------------------------------------------------------
    // Gemini
    // -----------------------------------------------------------------------

    private fun geminiAdapter() = GeminiAdapter(client, server.url("/").toString())

    @Test
    fun `gemini with image adds an inlineData part and uses the label prompt`() {
        val envelope = """{"candidates":[{"content":{"parts":[{"text":${quotedLabelItem()}}]}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        runBlocking { geminiAdapter().parse("", "gemini-2.5-flash", "KEY", imageBytes) }

        val recorded = JSONObject(server.takeRequest().body.readUtf8())
        val systemText = recorded.getJSONObject("systemInstruction").getJSONArray("parts")
            .getJSONObject(0).getString("text")
        assertTrue(systemText.contains("Nutrition Facts"))
        val parts = recorded.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        val inlineData = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("image/jpeg", inlineData.getString("mimeType"))
        assertEquals(expectedB64, inlineData.getString("data"))
        assertEquals("Read this nutrition label.", parts.getJSONObject(0).getString("text"))
    }

    @Test
    fun `gemini without image has no inlineData part and uses the meal prompt`() {
        val envelope = """{"candidates":[{"content":{"parts":[{"text":${quotedLabelItem()}}]}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        runBlocking { geminiAdapter().parse("eggs and oatmeal", "gemini-2.5-flash", "KEY") }

        val recorded = JSONObject(server.takeRequest().body.readUtf8())
        val systemText = recorded.getJSONObject("systemInstruction").getJSONArray("parts")
            .getJSONObject(0).getString("text")
        assertFalse(systemText.contains("Nutrition Facts"))
        val parts = recorded.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertFalse(parts.getJSONObject(0).has("inlineData"))
        assertEquals("eggs and oatmeal", parts.getJSONObject(0).getString("text"))
    }

    // -----------------------------------------------------------------------
    // OpenAI
    // -----------------------------------------------------------------------

    private fun openAiAdapter() = OpenAiAdapter(client, server.url("/v1/chat/completions").toString())

    @Test
    fun `openai with image sends an array content with a data url and uses the label prompt`() {
        val envelope = """{"choices":[{"message":{"content":${quotedLabelItem()}}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        runBlocking { openAiAdapter().parse("", "gpt-4o-mini", "KEY", imageBytes) }

        val recorded = JSONObject(server.takeRequest().body.readUtf8())
        val messages = recorded.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("Nutrition Facts"))
        val userContent = messages.getJSONObject(1).get("content")
        assertTrue("expected content to be an array, got $userContent", userContent is org.json.JSONArray)
        val arr = userContent as org.json.JSONArray
        assertEquals(2, arr.length())
        assertEquals("text", arr.getJSONObject(0).getString("type"))
        assertEquals("Read this nutrition label.", arr.getJSONObject(0).getString("text"))
        assertEquals("image_url", arr.getJSONObject(1).getString("type"))
        val url = arr.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertEquals("data:image/jpeg;base64,$expectedB64", url)
    }

    @Test
    fun `openai without image sends a plain string content and uses the meal prompt`() {
        val envelope = """{"choices":[{"message":{"content":${quotedLabelItem()}}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        runBlocking { openAiAdapter().parse("eggs and oatmeal", "gpt-4o-mini", "KEY") }

        val recorded = JSONObject(server.takeRequest().body.readUtf8())
        val messages = recorded.getJSONArray("messages")
        assertFalse(messages.getJSONObject(0).getString("content").contains("Nutrition Facts"))
        val userContent = messages.getJSONObject(1).get("content")
        assertTrue("expected content to be a plain string, got $userContent", userContent is String)
        assertEquals("eggs and oatmeal", userContent)
    }

    // -----------------------------------------------------------------------
    // Anthropic
    // -----------------------------------------------------------------------

    private fun anthropicAdapter() = AnthropicAdapter(client, server.url("/v1/messages").toString())

    @Test
    fun `anthropic with image sends an array content with a base64 image block and uses the label prompt`() {
        val envelope = """{"content":[{"type":"text","text":${quotedLabelItem()}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        runBlocking { anthropicAdapter().parse("", "claude-haiku-4-5", "KEY", imageBytes) }

        val recorded = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(recorded.getString("system").contains("Nutrition Facts"))
        val userContent = recorded.getJSONArray("messages").getJSONObject(0).get("content")
        assertTrue("expected content to be an array, got $userContent", userContent is org.json.JSONArray)
        val arr = userContent as org.json.JSONArray
        assertEquals(2, arr.length())
        assertEquals("image", arr.getJSONObject(0).getString("type"))
        val source = arr.getJSONObject(0).getJSONObject("source")
        assertEquals("base64", source.getString("type"))
        assertEquals("image/jpeg", source.getString("media_type"))
        assertEquals(expectedB64, source.getString("data"))
        assertEquals("text", arr.getJSONObject(1).getString("type"))
        assertEquals("Read this nutrition label.", arr.getJSONObject(1).getString("text"))
    }

    @Test
    fun `anthropic without image sends a plain string content and uses the meal prompt`() {
        val envelope = """{"content":[{"type":"text","text":${quotedLabelItem()}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        runBlocking { anthropicAdapter().parse("eggs and oatmeal", "claude-haiku-4-5", "KEY") }

        val recorded = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(recorded.getString("system").contains("Nutrition Facts"))
        val userContent = recorded.getJSONArray("messages").getJSONObject(0).get("content")
        assertTrue("expected content to be a plain string, got $userContent", userContent is String)
        assertEquals("eggs and oatmeal", userContent)
    }

    // -----------------------------------------------------------------------
    // Label response parses through the existing extraction path
    // -----------------------------------------------------------------------

    @Test
    fun `a label response parses into a ParsedFoodItem`() {
        val envelope = """{"content":[{"type":"text","text":${quotedLabelItem()}}]}"""
        server.enqueue(MockResponse().setBody(envelope).setResponseCode(200))

        val result = runBlocking { anthropicAdapter().parse("", "claude-haiku-4-5", "KEY", imageBytes) }

        assertTrue("expected Success, got $result", result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals("Acme Granola Bar", success.mealName)
        assertEquals(1, success.items.size)
        val item = success.items[0]
        assertEquals("Acme Granola Bar", item.name)
        assertEquals(1.0, item.displayQuantity, 0.001)
        assertEquals("serving", item.unit)
        assertEquals(42.0, item.grams)
        assertEquals(190.0, item.kcal, 0.001)
        assertEquals(3.0, item.proteinG, 0.001)
        assertEquals(7.0, item.fatG, 0.001)
        assertEquals(29.0, item.carbG, 0.001)
        assertTrue(item.needsConfirmation)
    }
}
