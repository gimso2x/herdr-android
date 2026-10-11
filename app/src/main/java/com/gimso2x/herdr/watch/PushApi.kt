package com.gimso2x.herdr.watch

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resumeWithException

class PushApi(private val client: OkHttpClient = sharedClient) {
    suspend fun register(origin: String, cookie: String, installation: String, token: String): Int =
        send(origin, cookie, "POST", JSONObject().put("installation_id", installation).put("fcm_token", token))

    suspend fun unregister(origin: String, cookie: String, installation: String): Int =
        send(origin, cookie, "DELETE", JSONObject().put("installation_id", installation))

    private suspend fun send(origin: String, cookie: String, method: String, json: JSONObject): Int {
        val request = Request.Builder().url("$origin/api/android-push/devices").header("Cookie", cookie)
            .method(method, json.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).await().use { it.code }
    }

    suspend fun detail(event: PushEvent, cookie: String): String? {
        val call = client.newCall(Request.Builder().url("${event.origin}/api/android-push/events/${event.eventId}")
            .header("Cookie", cookie).build())
        return call.await().use { response ->
            if (response.code != 200) return@use null
            coroutineScope {
                val read = async(Dispatchers.IO) {
                    val source = response.body?.source() ?: return@async null
                    // Prevent a private-detail response from growing without bound.
                    if (source.request(MAX_DETAIL_BYTES + 1)) null else source.buffer.readUtf8()
                }
                try { read.await() } catch (e: CancellationException) { call.cancel(); throw e }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

    companion object {
        private const val MAX_DETAIL_BYTES = 65_536L
        private val sharedClient: OkHttpClient by lazy { client() }
        fun client(): OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
}
