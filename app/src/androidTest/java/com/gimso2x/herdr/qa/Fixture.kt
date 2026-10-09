package com.gimso2x.herdr.qa

import android.util.Base64
import com.gimso2x.herdr.HerdrConfig
import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.access.ServiceToken
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

/**
 * A MockWebServer on 127.0.0.1 inside the test process that also plays Cloudflare Access: its
 * `/api/health` hands out an unsigned test JWT cookie. [host] is only the name the app uses (it
 * must be a loopback name allowed by the debug network config); the server binds to 127.0.0.1. [start] points the debug base-URL override
 * at it, so call it AFTER [Qa.resetState] (which clears the override).
 */
class Fixture(private val host: String = "127.0.0.1") {
    val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    val log = CopyOnWriteArrayList<RecordedRequest>()

    /** Every cookie value handed out by `/api/health`, in order. */
    val issued = CopyOnWriteArrayList<String>()

    @Volatile var clock: Clock = Clock.SYSTEM

    @Volatile var secureCookie: Boolean = true

    /** HTTP status of the n-th (1-based) `/api/health` request. */
    @Volatile var healthStatus: (Int) -> Int = { 200 }

    /** Token the fixture expects in the Access headers; mismatches answer 403. */
    @Volatile var expectedToken: ServiceToken? = Qa.syntheticToken()

    private val healthCount = AtomicInteger()
    private val server = MockWebServer()
    lateinit var base: String
        private set

    init {
        routes["/api/health"] = { health(it) }
    }

    fun start(): Fixture {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                log += request
                val path = request.path.orEmpty().substringBefore('?')
                return routes[path]?.invoke(request) ?: MockResponse().setResponseCode(404).setBody("not found")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        base = "http://$host:${server.port}"
        HerdrConfig.setDebugBaseUrl(base)
        return this
    }

    fun stop() {
        runCatching { server.shutdown() }
    }

    fun healthRequests(): Int = healthCount.get()

    fun count(path: String): Int = log.count { it.path.orEmpty().substringBefore('?') == path }

    fun html(title: String, body: String, script: String = ""): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .setHeader("Cache-Control", "no-store")
            .setBody(page(title, body, script))

    private fun health(request: RecordedRequest): MockResponse {
        val n = healthCount.incrementAndGet()
        val expected = expectedToken
        if (expected != null &&
            (request.getHeader("CF-Access-Client-Id") != expected.clientId ||
                request.getHeader("CF-Access-Client-Secret") != expected.clientSecret)
        ) {
            return MockResponse().setResponseCode(403)
        }
        val status = healthStatus(n)
        if (status != 200) return MockResponse().setResponseCode(status)
        val value = jwt(clock.nowMillis() / 1000 + 400, n)
        issued += value
        val attributes = if (secureCookie) "Path=/; Secure; HttpOnly" else "Path=/; HttpOnly"
        return MockResponse().setResponseCode(200).addHeader("Set-Cookie", "CF_Authorization=$value; $attributes").setBody("ok")
    }

    companion object {
        fun page(title: String, body: String, script: String = ""): String =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>$title</title></head><body>$body<script>$script</script></body></html>"

        /** Unsigned test JWT: `iss=qa-fixture`, the given `exp`, and a serial so every value differs. */
        fun jwt(expSeconds: Long, serial: Int): String {
            fun part(json: JSONObject): String =
                Base64.encodeToString(
                    json.toString().toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                )
            val header = JSONObject().put("alg", "none").put("typ", "JWT")
            val payload = JSONObject().put("iss", "qa-fixture").put("exp", expSeconds).put("jti", serial)
            return part(header) + "." + part(payload) + ".sig"
        }
    }
}
