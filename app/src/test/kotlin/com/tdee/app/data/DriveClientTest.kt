package com.tdee.app.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DriveClient] using OkHttp [MockWebServer].
 *
 * The retry backoff normally uses real `delay()`, which [kotlinx.coroutines.test.runTest]'s virtual
 * clock cannot skip because it runs inside `withContext(Dispatchers.IO)` (a real dispatcher, not the
 * test scheduler). Rather than restructure [DriveClient] around the test dispatcher, `backoffMs` is
 * an injectable constructor param — tests pass a tiny value so retries don't actually slow the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveClientTest {

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

    private fun client(
        token: suspend () -> String = { "TOKEN" },
        onUnauthorized: suspend () -> Unit = {},
    ) = DriveClient(
        client = httpClient,
        token = token,
        baseUrl = server.url("/").toString().trimEnd('/'),
        uploadUrl = server.url("/upload").toString().trimEnd('/'),
        onUnauthorized = onUnauthorized,
        backoffMs = 1L,
    )

    // -----------------------------------------------------------------------
    // ensureFolder
    // -----------------------------------------------------------------------

    @Test
    fun `ensureFolder uses existing folder id and does not create`() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"folder-1"}]}""").setResponseCode(200))

        val id = client().ensureFolder()

        assertEquals("folder-1", id)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ensureFolder creates folder when none found`() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[]}""").setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"id":"new-folder"}""").setResponseCode(200))

        val id = client().ensureFolder()

        assertEquals("new-folder", id)
        assertEquals(2, server.requestCount)
    }

    // -----------------------------------------------------------------------
    // upload
    // -----------------------------------------------------------------------

    @Test
    fun `upload sends multipart with metadata and content parts`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"id":"file-1","name":"backup.json","createdTime":"2026-08-10T00:00:00Z"}""")
                .setResponseCode(200),
        )

        val result = client().upload("folder-1", "backup.json", """{"data":true}""")

        assertEquals("file-1", result.id)
        assertEquals("backup.json", result.name)
        assertNull(result.size)

        val recorded = server.takeRequest()
        val contentType = recorded.getHeader("Content-Type").orEmpty()
        assertTrue("expected multipart/related, got $contentType", contentType.contains("multipart/related"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"name\":\"backup.json\""))
        assertTrue(body.contains("\"parents\":[\"folder-1\"]"))
        assertTrue(body.contains("""{"data":true}"""))
        // exactly 2 parts: split on the boundary and count non-empty segments containing our markers
        val partCount = Regex("Content-Type: application/json").findAll(body).count()
        assertEquals(2, partCount)
    }

    // -----------------------------------------------------------------------
    // list
    // -----------------------------------------------------------------------

    @Test
    fun `list parses files including one with no size`() = runTest {
        val body = """
            {"files":[
              {"id":"f1","name":"a.json","createdTime":"2026-08-10T00:00:00Z","size":"123"},
              {"id":"f2","name":"b.json","createdTime":"2026-08-09T00:00:00Z"}
            ]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))

        val files = client().list("folder-1")

        assertEquals(2, files.size)
        assertEquals("f1", files[0].id)
        assertEquals(123L, files[0].size)
        assertEquals("f2", files[1].id)
        assertNull(files[1].size)
    }

    // -----------------------------------------------------------------------
    // download
    // -----------------------------------------------------------------------

    @Test
    fun `download returns exact body`() = runTest {
        server.enqueue(MockResponse().setBody("""{"raw":"content"}""").setResponseCode(200))

        val content = client().download("file-1")

        assertEquals("""{"raw":"content"}""", content)
    }

    // -----------------------------------------------------------------------
    // retry: 429 / 5xx / network
    // -----------------------------------------------------------------------

    @Test
    fun `503 twice then success succeeds after 3 attempts`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"raw":"ok"}""").setResponseCode(200))

        val content = client().download("file-1")

        assertEquals("""{"raw":"ok"}""", content)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `three consecutive 503s throw Server`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val ex = runCatching { client().download("file-1") }.exceptionOrNull()

        assertTrue(ex is DriveException)
        assertEquals(DriveError.Server, (ex as DriveException).error)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `429 maps to RateLimited after retries`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }

        val ex = runCatching { client().download("file-1") }.exceptionOrNull()

        assertTrue(ex is DriveException)
        assertEquals(DriveError.RateLimited, (ex as DriveException).error)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `IOException maps to Network`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val ex = runCatching { client().download("file-1") }.exceptionOrNull()

        assertTrue(ex is DriveException)
        assertEquals(DriveError.Network, (ex as DriveException).error)
    }

    // -----------------------------------------------------------------------
    // 401 reauth path
    // -----------------------------------------------------------------------

    @Test
    fun `first 401 triggers reauth, retries with new token, then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"raw":"ok"}""").setResponseCode(200))

        var calls = 0
        var reauthCalls = 0
        val driveClient = client(
            token = { calls++; if (calls == 1) "OLD_TOKEN" else "NEW_TOKEN" },
            onUnauthorized = { reauthCalls++ },
        )

        val content = driveClient.download("file-1")

        assertEquals("""{"raw":"ok"}""", content)
        assertEquals(1, reauthCalls)
        assertEquals(2, server.requestCount)
        server.takeRequest() // first, 401'd request
        val secondRequest = server.takeRequest()
        assertEquals("Bearer NEW_TOKEN", secondRequest.getHeader("Authorization"))
    }

    @Test
    fun `401 twice throws NeedsAuth and onUnauthorized invoked once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        var reauthCalls = 0
        val driveClient = client(onUnauthorized = { reauthCalls++ })

        val ex = runCatching { driveClient.download("file-1") }.exceptionOrNull()

        assertTrue(ex is DriveException)
        assertEquals(DriveError.NeedsAuth, (ex as DriveException).error)
        assertEquals(1, reauthCalls)
        assertEquals(2, server.requestCount)
    }

    // -----------------------------------------------------------------------
    // prune
    // -----------------------------------------------------------------------

    @Test
    fun `prune deletes only the oldest files beyond keep`() = runTest {
        // The API returns files already ordered newest-first (createdTime desc); DriveClient just
        // parses that order, so f1..f13 here stands in for "newest..oldest" and drop(10) should
        // delete the last 3 (f11, f12, f13).
        val files = (1..13).map { i -> """{"id":"f$i","name":"n$i","createdTime":"t$i"}""" }
        server.enqueue(MockResponse().setBody("""{"files":[${files.joinToString(",")}]}""").setResponseCode(200))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200)) }

        client().prune("folder-1", keep = 10)

        assertEquals(4, server.requestCount) // 1 list + 3 deletes
        server.takeRequest() // list
        val deletedPaths = (1..3).map { server.takeRequest().path }
        // oldest 3 = f11, f12, f13 (list is newest-first, drop(10) keeps the tail)
        assertTrue(deletedPaths.all { it != null })
        assertTrue(deletedPaths.any { it!!.contains("f11") })
        assertTrue(deletedPaths.any { it!!.contains("f12") })
        assertTrue(deletedPaths.any { it!!.contains("f13") })
    }

    @Test
    fun `prune swallows a failing delete and continues`() = runTest {
        val files = (1..12).map { i -> """{"id":"f$i","name":"n$i","createdTime":"t$i"}""" }
        server.enqueue(MockResponse().setBody("""{"files":[${files.joinToString(",")}]}""").setResponseCode(200))
        // 2 files to delete (f11, f12); first delete fails 3x (exhausts retry -> Server error, swallowed),
        // second delete succeeds.
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        server.enqueue(MockResponse().setResponseCode(200))

        try {
            client().prune("folder-1", keep = 10)
        } catch (e: Exception) {
            fail("prune must not propagate delete failures: $e")
        }

        assertEquals(5, server.requestCount) // 1 list + 3 failed attempts + 1 successful delete
    }

    // -----------------------------------------------------------------------
    // unmapped error status
    // -----------------------------------------------------------------------

    @Test
    fun `unmapped 400 maps to Unknown carrying the provider message`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"error":{"message":"Rate limit exceeded"}}""").setResponseCode(400),
        )

        val ex = runCatching { client().download("file-1") }.exceptionOrNull()

        assertTrue(ex is DriveException)
        val error = (ex as DriveException).error
        assertTrue(error is DriveError.Unknown)
        assertEquals("Rate limit exceeded", (error as DriveError.Unknown).message)
        assertEquals(1, server.requestCount)
    }
}
