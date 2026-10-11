package com.gimso2x.herdr.watch

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushApiTest {
    private val origin = "https://fixture.example"
    private val event = PushEvent(UUID.randomUUID().toString(), AlertKind.NEEDS_INPUT, "qa:p1", origin, 1)

    @Test fun authenticatedIdempotentWritesAndReadOnlyDetailUseExactApi() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if (chain.request().method == "GET") 200 else 204)
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val api = PushApi(client)
            val install = UUID.randomUUID().toString()
            assertEquals(204, api.register(origin, "CF_Authorization=fixture", install, "fixture-token"))
            assertEquals(204, api.unregister(origin, "CF_Authorization=fixture", install))
            assertEquals("{}", api.detail(event, "CF_Authorization=fixture"))
            assertEquals(listOf("POST", "DELETE", "GET"), requests.map { it.method })
            assertEquals(listOf("/api/android-push/devices", "/api/android-push/devices",
                "/api/android-push/events/${event.eventId}"), requests.map { it.url.encodedPath })
            assertTrue(requests.all { it.header("Cookie") == "CF_Authorization=fixture" })
            assertTrue(requests.all { it.header("CF-Access-Client-Secret") == null })
            fun json(index: Int): JSONObject {
                val buffer = okio.Buffer()
                requests[index].body!!.writeTo(buffer)
                return JSONObject(buffer.readUtf8())
            }
            assertEquals(install, json(0).getString("installation_id"))
            assertEquals("fixture-token", json(0).getString("fcm_token"))
            assertEquals(setOf("installation_id"), json(1).keys().asSequence().toSet())
            val productionClient = PushApi.client()
            assertFalse(productionClient.followRedirects)
            assertFalse(productionClient.followSslRedirects)
        } finally { client.dispatcher.executorService.shutdown() }
    }

    @Test fun networkOrGoneDetailDoesNotCreateTextAndOversizedBodyIsRejected() = runBlocking {
        for ((code, body) in listOf(404 to "gone", 500 to "failed", 200 to "x".repeat(65_537))) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                    .code(code).body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            try { assertNull(PushApi(client).detail(event, "CF_Authorization=fixture")) }
            finally { client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun cancellingWorkCancelsTheExactNetworkCallWithoutWaitingForResponse() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor { chain ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            answered.countDown()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(200).body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val job = async(Dispatchers.Default) { PushApi(client).detail(event, "CF_Authorization=fixture") }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(10, TimeUnit.SECONDS) })
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { answered.await(10, TimeUnit.SECONDS) })
        } finally { release.countDown(); client.dispatcher.executorService.shutdown() }
    }
}
