package com.gimso2x.herdr.access

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessCookieBootstrapperTest {
    private lateinit var server: MockWebServer
    private lateinit var bootstrapper: AccessCookieBootstrapper
    private val token = ServiceToken("fixture-id.access", "fixture-secret-not-real")
    private val exp = 1_900_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        bootstrapper = AccessCookieBootstrapper(client, server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetch(t: ServiceToken = token): BootstrapResult = runBlocking { bootstrapper.fetch(t) }

    private fun okWithCookie(cookieHeader: String) =
        MockResponse().setResponseCode(200).addHeader("Set-Cookie", cookieHeader).setBody("{}")

    @Test
    fun ok200WithCookieIsSuccessWithExp() {
        val header = TestJwt.setCookie(TestJwt.unsigned(exp))
        server.enqueue(okWithCookie(header))
        val success = fetch() as BootstrapResult.Success
        assertEquals(header, success.setCookieHeader)
        assertEquals(exp, success.expEpochSeconds)
    }

    @Test
    fun successCarriesTheServerDateHeaderAsServerTime() {
        server.enqueue(
            okWithCookie(TestJwt.setCookie(TestJwt.unsigned(exp)))
                .setHeader("Date", "Sun, 06 Nov 1994 08:49:37 GMT"),
        )
        val success = fetch() as BootstrapResult.Success
        assertEquals(784111777000L, success.serverTimeMillis)
    }

    @Test
    fun missingOrMalformedDateHeaderLeavesServerTimeUnknown() {
        server.enqueue(okWithCookie(TestJwt.setCookie(TestJwt.unsigned(exp))).setHeader("Date", "not a date"))
        assertEquals(null, (fetch() as BootstrapResult.Success).serverTimeMillis)
    }

    @Test
    fun sendsGetToHealthWithBothAccessHeaders() {
        server.enqueue(okWithCookie(TestJwt.setCookie(TestJwt.unsigned(exp))))
        fetch()
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/api/health", request.path)
        assertEquals(token.clientId, request.getHeader("CF-Access-Client-Id"))
        assertEquals(token.clientSecret, request.getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun redirectToCloudflareAccessIsInvalidToken() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "https://x.cloudflareaccess.com/cdn-cgi/access/login/herdr"),
        )
        assertEquals(BootstrapResult.InvalidToken, fetch())
    }

    @Test
    fun status401IsInvalidToken() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(BootstrapResult.InvalidToken, fetch())
    }

    @Test
    fun status403IsInvalidToken() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(BootstrapResult.InvalidToken, fetch())
    }

    @Test
    fun ok200WithoutCookieIsStrictModeOrNoCookie() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertEquals(BootstrapResult.StrictModeOrNoCookie, fetch())
    }

    @Test
    fun disconnectIsNetwork() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(BootstrapResult.Network, fetch())
    }

    @Test
    fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/elsewhere").toString()))
        assertEquals(BootstrapResult.Unexpected(302), fetch())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectToOtherHostIsUnexpected() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://evil.example.com/cloudflareaccess.com"))
        assertEquals(BootstrapResult.Unexpected(302), fetch())
    }

    @Test
    fun redirectWithoutLocationIsUnexpected() {
        server.enqueue(MockResponse().setResponseCode(302))
        assertEquals(BootstrapResult.Unexpected(302), fetch())
    }

    @Test
    fun serverErrorIsUnexpectedWithCode() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(BootstrapResult.Unexpected(500), fetch())
    }

    // --- malformed input: must never throw; mapping documented on AccessCookieBootstrapper ---

    @Test
    fun setCookieWithoutEqualsIsStrictModeOrNoCookie() {
        server.enqueue(okWithCookie("CF_Authorization"))
        assertEquals(BootstrapResult.StrictModeOrNoCookie, fetch())
    }

    @Test
    fun garbageSetCookieHeadersAreStrictModeOrNoCookie() {
        val garbage = listOf("garbage", ";;;", "=", "=value", "other=1; Path=/", "cf_authorization=lowercase", "CF_Authorization=")
        for (header in garbage) {
            server.enqueue(okWithCookie(header))
            assertEquals("header: $header", BootstrapResult.StrictModeOrNoCookie, fetch())
        }
    }

    @Test
    fun nonJwtCookieValueIsUnexpected200() {
        server.enqueue(okWithCookie("CF_Authorization=not-a-jwt; Path=/"))
        assertEquals(BootstrapResult.Unexpected(200), fetch())
    }

    @Test
    fun payloadNotBase64IsUnexpected200() {
        server.enqueue(okWithCookie("CF_Authorization=aaa.!!!.ccc; Path=/"))
        assertEquals(BootstrapResult.Unexpected(200), fetch())
    }

    @Test
    fun payloadNotJsonIsUnexpected200() {
        server.enqueue(okWithCookie(TestJwt.setCookie(TestJwt.withRawPayload("this is not json"))))
        assertEquals(BootstrapResult.Unexpected(200), fetch())
    }

    @Test
    fun jwtWithoutExpIsUnexpected200() {
        server.enqueue(okWithCookie(TestJwt.setCookie(TestJwt.unsigned(null))))
        assertEquals(BootstrapResult.Unexpected(200), fetch())
    }

    @Test
    fun jwtWithNonNumericExpIsUnexpected200() {
        server.enqueue(okWithCookie(TestJwt.setCookie(TestJwt.withRawPayload("{\"iss\":\"qa-fixture\",\"exp\":\"soon\"}"))))
        assertEquals(BootstrapResult.Unexpected(200), fetch())
    }

    @Test
    fun tokenWithControlCharactersIsInvalidTokenWithoutRequest() {
        val result = fetch(ServiceToken("id", "bad\nsecret"))
        assertEquals(BootstrapResult.InvalidToken, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun successToStringDoesNotLeakCookie() {
        val header = TestJwt.setCookie(TestJwt.unsigned(exp))
        val text = BootstrapResult.Success(header, exp).toString()
        assertFalse(text.contains("CF_Authorization"))
        assertTrue(text.contains(exp.toString()))
    }
}
