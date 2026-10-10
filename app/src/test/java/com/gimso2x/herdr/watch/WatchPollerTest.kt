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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    private fun poller(httpClient: OkHttpClient = client): WatchPoller {
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
        return WatchPoller(
            session = session,
            client = httpClient,
            baseUrl = "http://127.0.0.1:1",
            cookie = { "CF_Authorization=x" },
            tracker = tracker,
            onAlerts = { alerts += it },
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

    private suspend fun awaitLatch(latch: CountDownLatch): Boolean =
        withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) }

    private companion object {
        const val BLOCKED_BODY =
            """{"snapshot":{"workspaces":[],"panes":[{"pane_id":"w1:p1","agent_status":"blocked","agent":"pi"}]}}"""
    }
}
