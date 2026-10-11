package com.gimso2x.herdr.shell

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DownloadPreflightTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private val preflight = DownloadPreflight()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        other = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun url(path: String = "/f.bin") = server.url(path).toString()

    @Test
    fun statusClassification() {
        for (code in listOf(200, 206, 204, 416)) assertEquals("$code", PreflightResult.OK, DownloadPreflight.classify(code))
        for (code in listOf(301, 302, 303, 307, 308, 401, 403)) {
            assertEquals("$code", PreflightResult.ACCESS_REJECTED, DownloadPreflight.classify(code))
        }
        for (code in listOf(400, 404, 429, 500, 503)) assertEquals("$code", PreflightResult.FAILED, DownloadPreflight.classify(code))
    }

    @Test
    fun directAnswerIsOkAndCarriesTheCookieOnlyToTheOrigin() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        assertEquals(PreflightResult.OK, preflight.check(url(), "CF_Authorization=abc", "UA/1"))
        val request = server.takeRequest()
        assertEquals("CF_Authorization=abc", request.getHeader("Cookie"))
        assertEquals("UA/1", request.getHeader("User-Agent"))
        assertEquals("bytes=0-0", request.getHeader("Range"))
    }

    @Test
    fun redirectIsRefusedAndNeverFollowedWithTheCookie() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/login").toString()))
        assertEquals(PreflightResult.ACCESS_REJECTED, preflight.check(url(), "CF_Authorization=abc", null))
        assertEquals(1, server.requestCount)
        assertEquals("the redirect target must never be contacted", 0, other.requestCount)
        assertNull(other.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun unauthorizedAndForbiddenAreAccessRejections() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(PreflightResult.ACCESS_REJECTED, preflight.check(url(), null, null))
        assertEquals(PreflightResult.ACCESS_REJECTED, preflight.check(url(), null, null))
    }

    @Test
    fun emptyFileAnswers416AndIsStillOk() {
        server.enqueue(MockResponse().setResponseCode(416))
        assertEquals(PreflightResult.OK, preflight.check(url(), null, null))
    }

    @Test
    fun errorStatusAndNetworkFailureAreFailures() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(PreflightResult.FAILED, preflight.check(url(), null, null))
        val dead = url("/gone")
        server.shutdown()
        assertEquals(PreflightResult.FAILED, preflight.check(dead, null, null))
        assertEquals(PreflightResult.FAILED, preflight.check("not a url", null, null))
    }
}
