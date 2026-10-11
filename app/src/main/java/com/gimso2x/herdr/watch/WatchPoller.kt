package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.AccessSession
import com.gimso2x.herdr.access.BootstrapResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * One `GET /api/session` per [pollOnce]; polls are serialized so the loop and test hooks never interleave.
 *
 * Alerts are dispatched through [onAlerts] as soon as the status sample is classified. The optional
 * detail line of each alert is fetched afterwards, off the polling path: at most [maxConcurrentDetails]
 * run at once, each capped at [AlertDetail.TIMEOUT_MS], and a finished one is reported through
 * [onAlertDetail] (never for a failed fetch). [pollOnce] therefore never waits for detail work;
 * [awaitDetails] does, for tests and diagnostics.
 */
class WatchPoller(
    private val session: AccessSession,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val cookie: () -> String?,
    private val tracker: StatusTracker,
    private val onAlerts: (List<Alert>) -> Unit,
    private val onAlertDetail: (Alert) -> Unit = {},
    maxConcurrentDetails: Int = MAX_CONCURRENT_DETAILS,
    private val detailFetcher: suspend (kind: AlertKind, paneId: String, cookie: String) -> String? =
        { kind, paneId, cookieHeader -> AlertDetail.fetch(client, baseUrl, cookieHeader, kind, paneId) },
) {
    private val mutex = Mutex()
    private val sequence = AtomicLong()
    private var forceRefresh = false
    private val detailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detailPermits = Semaphore(maxConcurrentDetails)

    suspend fun pollOnce(): PollResult = mutex.withLock {
        val result = withContext(Dispatchers.IO) { poll() }
        WatchRuntime.publish(result)
        result
    }

    /** Suspends until every detail fetch started so far has finished (and been reported). */
    suspend fun awaitDetails() {
        detailScope.coroutineContext[Job]?.children?.toList()?.joinAll()
    }

    /** Cancels in-flight detail work; call when the owning service stops. */
    fun close() {
        detailScope.cancel()
    }

    private fun fetchDetails(alerts: List<Alert>, cookieHeader: String) {
        for (alert in alerts) {
            detailScope.launch {
                val detail = detailPermits.withPermit {
                    withTimeoutOrNull(AlertDetail.TIMEOUT_MS + DETAIL_GRACE_MILLIS) {
                        detailFetcher(alert.kind, alert.paneId, cookieHeader)
                    }
                } ?: return@launch
                try {
                    onAlertDetail(alert.copy(detail = detail))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The detail line is optional enrichment: the base alert is already out.
                }
            }
        }
    }

    private suspend fun poll(): PollResult {
        val seq = sequence.incrementAndGet()
        val fresh = session.ensureFresh(force = forceRefresh)
        forceRefresh = false
        val cookieHeader = cookie()
        if (cookieHeader.isNullOrEmpty()) {
            val outcome = if (fresh == BootstrapResult.Network) PollOutcome.NETWORK else PollOutcome.AUTH
            return PollResult(seq, outcome)
        }
        val request = Request.Builder()
            .url("$baseUrl/api/session")
            .header("Cookie", cookieHeader)
            .build()
        return try {
            // The call is cancelled with the coroutine, including while the body is being read.
            val call = client.newCall(request)
            call.await().use { response ->
                val context = currentCoroutineContext()
                context.ensureActive()
                val body = if (response.code == 200) call.readBody(response) else null
                context.ensureActive()
                handle(seq, response.code, body, context, cookieHeader)
            }
        } catch (_: IOException) {
            PollResult(seq, PollOutcome.NETWORK)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

    /**
     * Reads the body off-thread so a stop while the read is blocked cancels the [Call] (closing the
     * socket unblocks the read) instead of leaving it alive until the body or timeouts complete.
     */
    private suspend fun Call.readBody(response: Response): String = coroutineScope {
        val reader = async(Dispatchers.IO) { response.body?.string().orEmpty() }
        try {
            reader.await()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
    }

    private suspend fun handle(
        seq: Long,
        code: Int,
        body: String?,
        context: CoroutineContext,
        cookieHeader: String,
    ): PollResult = when (code) {
        200 -> {
            val samples = SnapshotParser.parse(body.orEmpty())
            if (samples == null) {
                PollResult(seq, PollOutcome.PARSE_ERROR)
            } else {
                val alerts = tracker.update(samples)
                context.ensureActive() // never alert once the poll's owner has been stopped
                if (alerts.isNotEmpty()) {
                    onAlerts(alerts) // base alerts go out now; detail lines follow without blocking the next sample
                    fetchDetails(alerts, cookieHeader)
                }
                PollResult(seq, PollOutcome.OK, samples.size, alerts.size)
            }
        }
        302, 401, 403 -> {
            forceRefresh = true
            PollResult(seq, PollOutcome.AUTH)
        }
        else -> PollResult(seq, PollOutcome.HTTP_ERROR)
    }

    private companion object {
        const val MAX_CONCURRENT_DETAILS = 4
        const val DETAIL_GRACE_MILLIS = 1_000L
    }
}
