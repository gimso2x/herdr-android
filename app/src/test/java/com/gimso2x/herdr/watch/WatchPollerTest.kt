package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.AccessSession
import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.CookieInstaller
import com.gimso2x.herdr.access.CookieSink
import com.gimso2x.herdr.access.MemoryTokenStore
import com.gimso2x.herdr.access.ServiceToken
import com.gimso2x.herdr.access.TestJwt
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    private fun poller(): WatchPoller {
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
            client = client,
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

    private suspend fun awaitLatch(latch: CountDownLatch): Boolean =
        withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) }

    private companion object {
        const val BLOCKED_BODY =
            """{"snapshot":{"workspaces":[],"panes":[{"pane_id":"w1:p1","agent_status":"blocked","agent":"pi"}]}}"""
    }
}
