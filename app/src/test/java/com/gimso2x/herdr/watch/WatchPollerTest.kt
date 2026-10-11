package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.AccessSession
import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.CookieInstaller
import com.gimso2x.herdr.access.CookieSink
import com.gimso2x.herdr.access.MemoryTokenStore
import com.gimso2x.herdr.access.ServiceToken
import com.gimso2x.herdr.access.TestJwt
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses are held inside an application interceptor behind latches, so every ordering is
 * decided by the test (no sleeps): the "server" answers only when [release] is counted down.
 */
class WatchPollerTest {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val answered = CountDownLatch(1)
    private val callWasCancelled = AtomicBoolean()
    private val alerts = CopyOnWriteArrayList<Alert>()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "test never released the response" }
            callWasCancelled.set(chain.call().isCanceled())
            val response = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(BLOCKED_BODY.toResponseBody("application/json".toMediaType()))
                .build()
            answered.countDown()
            response
        }
        .build()

    private val tracker = StatusTracker().also {
        // Seed the pane as working so the held response is a blocked transition that must alert.
        it.update(listOf(PaneSample("w1:p1", AgentStatus.WORKING, "work")))
    }

    private val details = CopyOnWriteArrayList<Alert>()

    private fun poller(
        httpClient: OkHttpClient = client,
        tracker: StatusTracker = this.tracker,
        maxConcurrentDetails: Int = 4,
        detailFetcher: (suspend (AlertKind, String, String) -> String?)? = null,
    ): WatchPoller {
        val exp = 4_102_444_800L // far future: the session never refreshes
        val session = AccessSession(
            fetcher = { BootstrapResult.Success(TestJwt.setCookie(TestJwt.unsigned(exp)), exp) },
            store = MemoryTokenStore(ServiceToken("fixture-id", "fixture-secret")),
            installer = CookieInstaller(
                "http://127.0.0.1:1",
                object : CookieSink {
                    override fun setCookie(url: String, value: String) = Unit

                    override fun flush() = Unit
                },
            ),
        )
        val base = "http://127.0.0.1:1"
        return WatchPoller(
            session = session,
            client = httpClient,
            baseUrl = base,
            cookie = { "CF_Authorization=x" },
            tracker = tracker,
            onAlerts = { alerts += it },
            onAlertDetail = { details += it },
            maxConcurrentDetails = maxConcurrentDetails,
            detailFetcher = detailFetcher
                ?: { kind, paneId, cookie -> AlertDetail.fetch(httpClient, base, cookie, kind, paneId) },
        )
    }

    @After
    fun shutDown() {
        release.countDown()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun responseArrivingAfterCancellationDispatchesNothing() = runBlocking {
        val job = launch(Dispatchers.Default) { poller().pollOnce() }
        assertTrue("request never reached the network layer", awaitLatch(entered))

        job.cancel()
        val joined = withTimeoutOrNull(10_000) { job.join() }
        assertTrue("cancelled poll must not stay blocked on the in-flight call", joined != null)

        release.countDown()
        assertTrue("held response was never delivered", awaitLatch(answered))
        assertTrue("in-flight call must be cancelled with the coroutine", callWasCancelled.get())
        assertEquals(emptyList<Alert>(), alerts.toList())
    }

    @Test
    fun uncancelledPollDispatchesTheSameAlert() = runBlocking {
        val job = launch(Dispatchers.Default) { poller().pollOnce() }
        assertTrue("request never reached the network layer", awaitLatch(entered))

        release.countDown()
        job.join()

        assertEquals(listOf(AlertKind.NEEDS_INPUT), alerts.map { it.kind })
    }

    @Test
    fun cancellationDuringBodyReadCancelsTheCallAndDispatchesNothing() = runBlocking {
        val bodyEntered = CountDownLatch(1)
        val bodyGate = CountDownLatch(1)
        val callCanceled = AtomicBoolean()
        // Closing the socket is what unblocks a real body read; model it as "the call was cancelled".
        val bodyClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    callCanceled.set(true)
                    bodyGate.countDown()
                }
            })
            .addInterceptor { chain ->
                val source = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        bodyEntered.countDown()
                        check(bodyGate.await(30, TimeUnit.SECONDS)) { "test never released the body" }
                        if (callCanceled.get()) throw IOException("Canceled")
                        return -1
                    }

                    override fun timeout() = Timeout.NONE

                    override fun close() = Unit
                }
                val body = object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = source.buffer()
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()
        try {
            val job = launch(Dispatchers.Default) { poller(bodyClient).pollOnce() }
            assertTrue("body read never started", awaitLatch(bodyEntered))

            job.cancel()
            val joined = withTimeoutOrNull(10_000) { job.join() }
            assertTrue("cancelled poll must not stay blocked on the body read", joined != null)

            assertTrue("call must be cancelled while its body is being read", callCanceled.get())
            assertEquals(emptyList<Alert>(), alerts.toList())
        } finally {
            bodyGate.countDown()
            bodyClient.dispatcher.executorService.shutdown()
        }
    }

    private fun routedClient(requests: MutableList<String>, detailOk: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += "${request.method} ${request.url.encodedPath}${request.url.encodedQuery?.let { "?$it" }.orEmpty()}"
                val json = when (request.url.encodedPath) {
                    "/api/pane/prompt" -> """{"prompt":{"question":"Apply the fixture migration?\nsecond line","title":"t"},"suggestion":null}"""
                    "/api/pane/conversation" -> "{}"
                    else -> BLOCKED_BODY
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (!detailOk && request.url.encodedPath != "/api/session") 500 else 200)
                    .message("x")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun blockedAlertCarriesThePromptQuestionFromOneGet() = runBlocking {
        release.countDown()
        val requests = CopyOnWriteArrayList<String>()
        val routed = routedClient(requests, true)
        val p = poller(routed)
        p.pollOnce()
        p.awaitDetails()
        assertEquals(
            listOf("GET /api/session", "GET /api/pane/prompt?pane_id=w1%3Ap1"),
            requests.toList(),
        )
        assertEquals("the base alert went out without the detail", null, alerts.single().detail)
        val alert = details.single()
        assertEquals("Apply the fixture migration?", alert.detail)
        assertEquals("pi — 입력이 필요합니다\nApply the fixture migration?", alert.bigText)
        routed.dispatcher.executorService.shutdown()
    }

    @Test
    fun failedDetailRequestOmitsTheLineButStillAlerts() = runBlocking {
        release.countDown()
        val requests = CopyOnWriteArrayList<String>()
        val routed = routedClient(requests, false)
        val p = poller(routed)
        p.pollOnce()
        p.awaitDetails()
        assertEquals(2, requests.size)
        val alert = alerts.single()
        assertEquals(null, alert.detail)
        assertEquals("pi — 입력이 필요합니다", alert.bigText)
        assertEquals("a failed detail fetch reports nothing", emptyList<Alert>(), details.toList())
        routed.dispatcher.executorService.shutdown()
    }

    @Test
    fun slowDetailFetchNeitherDelaysTheBaseAlertNorTheNextStatusSample() = runBlocking {
        release.countDown()
        val detailEntered = CompletableDeferred<Unit>()
        val detailGate = CompletableDeferred<String?>()
        val p = poller { _, _, _ ->
            detailEntered.complete(Unit)
            detailGate.await() // a prompt endpoint that has not answered yet
        }
        try {
            val first = withTimeout(10_000) { p.pollOnce() }
            assertEquals(1, first.alertCount)
            assertEquals("base alert is out before any detail", listOf(AlertKind.NEEDS_INPUT), alerts.map { it.kind })
            assertEquals(null, alerts.single().detail)
            withTimeout(10_000) { detailEntered.await() } // the fetch is running, concurrently

            // The next status sample completes while the first alert's detail is still pending.
            val second = withTimeout(10_000) { p.pollOnce() }
            assertEquals(first.sequence + 1, second.sequence)
            assertEquals(emptyList<Alert>(), details.toList())

            detailGate.complete("the question")
            withTimeout(10_000) { p.awaitDetails() }
            assertEquals(listOf("the question"), details.map { it.detail })
            assertEquals(1, alerts.size)
        } finally {
            p.close()
        }
    }

    @Test
    fun detailFetchesRunConcurrentlyButAreBounded() = runBlocking {
        val paneIds = (1..5).map { "w1:p$it" }
        val panes = paneIds.joinToString(",") { """{"pane_id":"$it","agent_status":"blocked","agent":"pi"}""" }
        val blockedBody = """{"snapshot":{"workspaces":[],"panes":[$panes]}}"""
        val manyClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(blockedBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val seeded = StatusTracker().also { t ->
            t.update(paneIds.map { PaneSample(it, AgentStatus.WORKING, "work") })
        }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val twoRunning = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val p = poller(manyClient, seeded, maxConcurrentDetails = 2) { _, paneId, _ ->
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            if (now == 2) twoRunning.complete(Unit)
            gate.await()
            active.decrementAndGet()
            "detail of $paneId"
        }
        try {
            val result = withTimeout(10_000) { p.pollOnce() }
            assertEquals(5, result.alertCount)
            assertEquals("all five base alerts dispatched at once", 5, alerts.size)
            withTimeout(10_000) { twoRunning.await() } // two details in flight together
            gate.complete(Unit)
            withTimeout(10_000) { p.awaitDetails() }
            assertEquals(5, details.size)
            assertEquals("never more than the bound in flight", 2, peak.get())
        } finally {
            manyClient.dispatcher.executorService.shutdown()
            p.close()
        }
    }

    private suspend fun awaitLatch(latch: CountDownLatch): Boolean =
        withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) }

    private companion object {
        const val BLOCKED_BODY =
            """{"snapshot":{"workspaces":[],"panes":[{"pane_id":"w1:p1","agent_status":"blocked","agent":"pi"}]}}"""
    }
}
