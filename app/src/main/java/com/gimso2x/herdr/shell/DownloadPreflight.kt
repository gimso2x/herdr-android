package com.gimso2x.herdr.shell

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of probing a download URL with the Access cookie before DownloadManager sees it. */
enum class PreflightResult {
    /** The origin itself answered with content: safe to enqueue. */
    OK,

    /** 3xx / 401 / 403: the cookie was not accepted (Access login redirect); renew and probe again. */
    ACCESS_REJECTED,

    /** Anything else (error status, network failure): do not enqueue. */
    FAILED,
}

/**
 * DownloadManager re-sends every request header (including the CF_Authorization cookie) on each
 * redirect hop and the app has no control once a request is enqueued. So the URL is first requested
 * with the app's own client, redirects disabled; only a direct 2xx answer lets the download proceed.
 */
class DownloadPreflight(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    /** Blocking; call off the main thread. */
    fun check(url: String, cookie: String?, userAgent: String?): PreflightResult {
        val httpUrl = url.toHttpUrlOrNull() ?: return PreflightResult.FAILED
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            // Only the status matters: ask for one byte; the body is never read.
            .header("Range", "bytes=0-0")
            .apply {
                cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            }
            .build()
        return try {
            client.newCall(request).execute().use { classify(it.code) }
        } catch (_: IOException) {
            PreflightResult.FAILED
        }
    }

    companion object {
        /** 416: an empty file cannot satisfy a byte range, but the origin did serve it. */
        fun classify(code: Int): PreflightResult = when (code) {
            in 200..299, 416 -> PreflightResult.OK
            in 300..399, 401, 403 -> PreflightResult.ACCESS_REJECTED
            else -> PreflightResult.FAILED
        }
    }
}
