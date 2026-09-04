package com.tdee.app.addfood

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [networkErrorMessage] and its wiring into [executeWithRetry]: the three
 * [IOException] shapes that can come out of a real call must each produce a distinct, honest
 * message while all keeping [ParseErrorKind.NETWORK] — see the branch's fix for
 * `executeWithRetry` asserting "No internet connection." on every network error regardless of
 * cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpSupportTest {

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

    // -----------------------------------------------------------------------
    // networkErrorMessage: pure wording, no I/O
    // -----------------------------------------------------------------------

    @Test
    fun `UnknownHostException reads as offline`() {
        assertEquals(
            "No internet connection.",
            networkErrorMessage(UnknownHostException("api.example.com"), "the meal parser"),
        )
    }

    @Test
    fun `SocketTimeoutException names the service and says it was slow`() {
        assertEquals(
            "Couldn't reach the meal parser in time — try again.",
            networkErrorMessage(java.net.SocketTimeoutException("timeout"), "the meal parser"),
        )
    }

    @Test
    fun `a generic IOException names the service without claiming the phone is offline`() {
        val message = networkErrorMessage(IOException("connection reset"), "Open Food Facts")
        assertEquals("Couldn't reach Open Food Facts — try again.", message)
    }

    // -----------------------------------------------------------------------
    // executeWithRetry: the real IOException shapes an OkHttp call can throw
    // -----------------------------------------------------------------------

    /** A [Dns] that fails every lookup, so a call throws a genuine [UnknownHostException]. */
    private class ThrowingDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
    }

    @Test
    fun `a DNS failure surfaces the offline message and stays NETWORK`() = runBlocking {
        val client = OkHttpClient.Builder().dns(ThrowingDns()).build()
        val request = Request.Builder().url(server.url("/")).build()

        val outcome = executeWithRetry(client, request, "the meal parser")

        assertTrue(outcome is HttpOutcome.Error)
        val failure = (outcome as HttpOutcome.Error).failure
        assertEquals(ParseErrorKind.NETWORK, failure.kind)
        assertEquals("No internet connection.", failure.message)
    }

    @Test
    fun `a socket timeout surfaces the slow-service message and stays NETWORK`() = runBlocking {
        val client = OkHttpClient.Builder()
            .readTimeout(Duration.ofMillis(200))
            .build()
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val request = Request.Builder().url(server.url("/")).build()

        val outcome = executeWithRetry(client, request, "the meal parser")

        assertTrue(outcome is HttpOutcome.Error)
        val failure = (outcome as HttpOutcome.Error).failure
        assertEquals(ParseErrorKind.NETWORK, failure.kind)
        assertEquals("Couldn't reach the meal parser in time — try again.", failure.message)
    }

    @Test
    fun `a dropped connection surfaces the unreachable message and stays NETWORK`() = runBlocking {
        val client = OkHttpClient()
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val request = Request.Builder().url(server.url("/")).build()

        val outcome = executeWithRetry(client, request, "the meal parser")

        assertTrue(outcome is HttpOutcome.Error)
        val failure = (outcome as HttpOutcome.Error).failure
        assertEquals(ParseErrorKind.NETWORK, failure.kind)
        assertEquals("Couldn't reach the meal parser — try again.", failure.message)
    }
}
